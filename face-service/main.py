"""
DeepFace ArcFace microservice for gallery-app face detection and recognition.

Endpoints
---------
GET  /health          – liveness probe
POST /detect          – detect faces in an uploaded image, return embeddings + crops
"""

import base64
import os
import tempfile
from contextlib import asynccontextmanager

import cv2
import numpy as np
import uvicorn
from deepface import DeepFace
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
from nudenet import NudeDetector

# ── Model config ─────────────────────────────────────────────────────────────
MODEL_NAME       = os.getenv("DEEPFACE_MODEL",    "ArcFace")
DETECTOR_BACKEND = os.getenv("DEEPFACE_DETECTOR", "retinaface")
MIN_CONFIDENCE   = float(os.getenv("DEEPFACE_MIN_CONFIDENCE", "0.5"))
NSFW_REVIEW_THRESHOLD = float(os.getenv("NSFW_REVIEW_THRESHOLD", "0.65"))
NSFW_EXPLICIT_THRESHOLD = float(os.getenv("NSFW_EXPLICIT_THRESHOLD", "0.85"))
NSFW_SCORING_VERSION = 2
_nsfw_detector = None

# Only genuinely exposed classes contribute to the NSFW score. Covered body
# parts, faces, feet, bellies and armpits are intentionally ignored to avoid
# the false positives produced by the original all-label weighted score.
EXPLICIT_CLASSES = {
    "FEMALE_GENITALIA_EXPOSED",
    "MALE_GENITALIA_EXPOSED",
    "ANUS_EXPOSED",
    "FEMALE_BREAST_EXPOSED",
    "BUTTOCKS_EXPOSED",
}


# ── Pre-warm model on startup ─────────────────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    print(f"[face-service] Pre-warming {MODEL_NAME} with {DETECTOR_BACKEND} …", flush=True)
    try:
        dummy = np.zeros((112, 112, 3), dtype=np.uint8)
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as f:
            cv2.imwrite(f.name, dummy)
            DeepFace.represent(
                img_path=f.name,
                model_name=MODEL_NAME,
                detector_backend=DETECTOR_BACKEND,
                enforce_detection=False,
                align=True,
            )
            os.unlink(f.name)
    except Exception as exc:
        print(f"[face-service] Warm-up warning (non-fatal): {exc}", flush=True)
    print(f"[face-service] Ready.", flush=True)
    yield


app = FastAPI(title="gallery-face-service", lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "detector": DETECTOR_BACKEND}


def get_nsfw_detector() -> NudeDetector:
    """Create NudeNet once and prefer CUDA when ONNX Runtime exposes it."""
    global _nsfw_detector
    if _nsfw_detector is not None:
        return _nsfw_detector

    detector = NudeDetector()

    try:
        import onnxruntime as ort

        available = ort.get_available_providers()
        if "CUDAExecutionProvider" in available:
            model_path = detector.onnx_session._model_path
            detector.onnx_session = ort.InferenceSession(
                model_path,
                providers=["CUDAExecutionProvider", "CPUExecutionProvider"],
            )
        active = detector.onnx_session.get_providers()
        print(f"[face-service] NudeNet providers: {active}", flush=True)
    except Exception as exc:
        print(f"[face-service] NudeNet GPU setup warning; using default session: {exc}", flush=True)

    _nsfw_detector = detector
    return detector


@app.post("/nsfw/detect")
async def detect_nsfw(file: UploadFile = File(...)):
    contents = await file.read()
    suffix = os.path.splitext(file.filename or "image.jpg")[1] or ".jpg"

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(contents)
        tmp_path = tmp.name

    try:
        detections = get_nsfw_detector().detect(tmp_path)

        explicit_detections = []
        for detection in detections:
            name = str(detection.get("class", ""))
            if name not in EXPLICIT_CLASSES:
                continue

            explicit_detections.append({
                "name": name,
                "score": float(detection.get("score", 0.0)),
            })

        explicit_detections.sort(key=lambda item: item["score"], reverse=True)
        score = max((item["score"] for item in explicit_detections), default=0.0)

        if score >= NSFW_EXPLICIT_THRESHOLD:
            level = "EXPLICIT"
        elif score >= NSFW_REVIEW_THRESHOLD:
            level = "REVIEW"
        else:
            level = "SAFE"

        return JSONResponse(content={
            "score": score,
            "level": level,
            "labels": explicit_detections,
            "scoringVersion": NSFW_SCORING_VERSION,
        })
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={
                "error": str(exc),
                "score": 0.0,
                "level": "UNKNOWN",
                "labels": [],
                "scoringVersion": NSFW_SCORING_VERSION,
            },
        )
    finally:
        os.unlink(tmp_path)


@app.post("/detect")
async def detect_faces(file: UploadFile = File(...)):
    """
    Detect faces in the uploaded image.

    Returns a JSON object:
    {
      "faces": [
        {
          "bbox": {"x": int, "y": int, "w": int, "h": int},
          "confidence": float,
          "embedding": [float, ...],   // 512-D ArcFace
          "crop_b64": "..."            // base64-encoded JPEG crop
        },
        ...
      ]
    }
    """
    contents = await file.read()

    with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
        tmp.write(contents)
        tmp_path = tmp.name

    try:
        results = DeepFace.represent(
            img_path=tmp_path,
            model_name=MODEL_NAME,
            detector_backend=DETECTOR_BACKEND,
            enforce_detection=False,
            align=True,
        )

        img = cv2.imread(tmp_path)
        if img is None:
            return JSONResponse(content={"faces": []})

        h_img, w_img = img.shape[:2]
        faces = []

        for r in results:
            confidence = float(r.get("face_confidence", 0.0))
            if confidence < MIN_CONFIDENCE:
                continue

            area = r.get("facial_area", {})
            x = int(area.get("x", 0))
            y = int(area.get("y", 0))
            w = int(area.get("w", 0))
            h = int(area.get("h", 0))

            if w < 20 or h < 20:
                continue  # skip tiny detections

            # Crop with 10% padding, clamped to image bounds
            pad = int(min(w, h) * 0.10)
            x1 = max(0, x - pad)
            y1 = max(0, y - pad)
            x2 = min(w_img, x + w + pad)
            y2 = min(h_img, y + h + pad)

            crop = img[y1:y2, x1:x2]
            ok, encoded = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, 85])
            crop_b64 = base64.b64encode(encoded.tobytes()).decode() if ok else ""

            embedding = r.get("embedding", [])
            # Normalise to unit vector (ArcFace already does this, but ensure it)
            arr = np.array(embedding, dtype=np.float32)
            norm = np.linalg.norm(arr)
            if norm > 0:
                arr = arr / norm
            embedding = arr.tolist()

            faces.append({
                "bbox": {"x": x, "y": y, "w": w, "h": h},
                "confidence": confidence,
                "embedding": embedding,
                "crop_b64": crop_b64,
            })

        return JSONResponse(content={"faces": faces})

    except Exception as exc:
        return JSONResponse(status_code=500, content={"error": str(exc), "faces": []})
    finally:
        os.unlink(tmp_path)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8082, log_level="info")