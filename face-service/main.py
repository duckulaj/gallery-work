"""
DeepFace ArcFace microservice for gallery-app face detection and recognition.

Endpoints
---------
GET  /health          - liveness/readiness and accelerator information
POST /detect          - detect faces in an uploaded image, return embeddings + crops
POST /nsfw/detect     - classify explicit-content detections with NudeNet
"""

import base64
import asyncio
import os
import tempfile
import time
from contextlib import asynccontextmanager
from importlib import metadata
from pathlib import Path
from typing import Any

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from nudenet import NudeDetector

DeepFace = None
_tf = None

# ── Model config ─────────────────────────────────────────────────────────────
MODEL_NAME = os.getenv("DEEPFACE_MODEL", "ArcFace")
DETECTOR_BACKEND = os.getenv("DEEPFACE_DETECTOR", "retinaface")
MIN_CONFIDENCE = float(os.getenv("DEEPFACE_MIN_CONFIDENCE", "0.5"))
MIN_FACE_SIZE = int(os.getenv("DEEPFACE_MIN_FACE_SIZE", "20"))
FACE_CROP_PADDING = float(os.getenv("DEEPFACE_CROP_PADDING", "0.10"))
JPEG_QUALITY = int(os.getenv("DEEPFACE_CROP_JPEG_QUALITY", "85"))
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(25 * 1024 * 1024)))
MAX_IMAGE_PIXELS = int(os.getenv("MAX_IMAGE_PIXELS", "60000000"))
MAX_CONCURRENT_INFERENCE = int(os.getenv("MAX_CONCURRENT_INFERENCE", "1"))
_inference_slots = asyncio.Semaphore(MAX_CONCURRENT_INFERENCE)

NSFW_REVIEW_THRESHOLD = float(os.getenv("NSFW_REVIEW_THRESHOLD", "0.65"))
NSFW_EXPLICIT_THRESHOLD = float(os.getenv("NSFW_EXPLICIT_THRESHOLD", "0.85"))
AI_REQUIRE_GPU = os.getenv("AI_REQUIRE_GPU", "false").lower() in {"1", "true", "yes"}
NSFW_SCORING_VERSION = 2

_nsfw_detector = None
_accelerators: dict[str, Any] = {
    "tensorflow": "initializing",
    "nudenet": "initializing",
}
_runtime_versions: dict[str, str] = {}

# Only genuinely exposed classes contribute to the NSFW score. Covered body
# parts, faces, feet, bellies and armpits are intentionally ignored to avoid
# false positives from an all-label weighted score.
EXPLICIT_CLASSES = {
    "FEMALE_GENITALIA_EXPOSED",
    "MALE_GENITALIA_EXPOSED",
    "ANUS_EXPOSED",
    "FEMALE_BREAST_EXPOSED",
    "BUTTOCKS_EXPOSED",
}


def _package_version(package_name: str) -> str:
    """Return an installed package version without making health checks fragile."""
    try:
        return metadata.version(package_name)
    except metadata.PackageNotFoundError:
        return "not-installed"


def _safe_unlink(path: str | None) -> None:
    """Remove a temporary file without masking the original request error."""
    if not path:
        return
    try:
        Path(path).unlink(missing_ok=True)
    except OSError as exc:
        print(f"[face-service] Could not remove temporary file {path}: {exc}", flush=True)


async def _save_bounded_upload(file: UploadFile, allowed_suffixes: set[str]) -> tuple[str, str, bytes]:
    """Stream an upload to disk while enforcing a service-local hard byte limit."""
    supplied_suffix = Path(file.filename or "image.jpg").suffix.lower()
    suffix = supplied_suffix if supplied_suffix in allowed_suffixes else ".jpg"
    total = 0
    header = bytearray()
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        path = tmp.name
        while chunk := await file.read(1024 * 1024):
            total += len(chunk)
            if total > MAX_UPLOAD_BYTES:
                _safe_unlink(path)
                raise ValueError(f"Upload exceeds {MAX_UPLOAD_BYTES} byte limit")
            if len(header) < 16:
                header.extend(chunk[: 16 - len(header)])
            tmp.write(chunk)
    return path, supplied_suffix, bytes(header)


def _runtime_info(tf_module) -> dict[str, str]:
    """Collect package versions useful when diagnosing container/GPU issues."""
    return {
        "pythonTensorFlow": str(tf_module.__version__),
        "tfKeras": _package_version("tf-keras"),
        "deepface": _package_version("deepface"),
        "opencv": str(cv2.__version__),
        "numpy": str(np.__version__),
        "nudenet": _package_version("nudenet"),
        "onnxruntimeGpu": _package_version("onnxruntime-gpu"),
        "fastapi": _package_version("fastapi"),
        "uvicorn": _package_version("uvicorn"),
    }


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
            # NudeNet constructs its own session. Recreate it with CUDA first if
            # the installed ONNX Runtime build exposes the CUDA provider.
            model_path = getattr(detector.onnx_session, "_model_path", None)
            if model_path:
                detector.onnx_session = ort.InferenceSession(
                    model_path,
                    providers=["CUDAExecutionProvider", "CPUExecutionProvider"],
                )
        active = detector.onnx_session.get_providers()
        print(f"[face-service] NudeNet providers: {active}", flush=True)
    except Exception as exc:
        print(
            f"[face-service] NudeNet GPU setup warning; using default session: {exc}",
            flush=True,
        )

    _nsfw_detector = detector
    return detector


# ── Application startup / model pre-warm ────────────────────────────────────
@asynccontextmanager
async def lifespan(app: FastAPI):
    global DeepFace, _accelerators, _runtime_versions, _tf

    from deepface import DeepFace as DeepFaceClass
    import tensorflow as tf

    _tf = tf

    # TensorFlow can reserve most VRAM by default. Cap this service so other GPU
    # consumers, such as Ollama, retain room to offload their models.
    gpu_memory_limit_mb = int(os.getenv("DEEPFACE_GPU_MEMORY_LIMIT_MB", "2048"))
    physical_gpus = tf.config.list_physical_devices("GPU")
    for gpu in physical_gpus:
        try:
            tf.config.set_logical_device_configuration(
                gpu,
                [tf.config.LogicalDeviceConfiguration(memory_limit=gpu_memory_limit_mb)],
            )
        except RuntimeError as exc:
            print(f"[face-service] Could not set GPU memory limit: {exc}", flush=True)

    DeepFace = DeepFaceClass
    nsfw_detector = get_nsfw_detector()

    tensorflow_gpus = [device.name for device in tf.config.list_physical_devices("GPU")]
    nudenet_providers = nsfw_detector.onnx_session.get_providers()
    _accelerators = {
        "tensorflow": tensorflow_gpus,
        "nudenet": nudenet_providers,
    }
    _runtime_versions = _runtime_info(tf)

    if AI_REQUIRE_GPU and (
        not tensorflow_gpus or "CUDAExecutionProvider" not in nudenet_providers
    ):
        raise RuntimeError(
            f"GPU acceleration is required but unavailable: {_accelerators}"
        )

    print(f"[face-service] Runtime versions: {_runtime_versions}", flush=True)
    print(f"[face-service] Accelerators: {_accelerators}", flush=True)
    print(
        f"[face-service] Pre-warming {MODEL_NAME} with {DETECTOR_BACKEND} ...",
        flush=True,
    )

    warmup_path = None
    try:
        dummy = np.zeros((112, 112, 3), dtype=np.uint8)
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
            warmup_path = tmp.name

        if not cv2.imwrite(warmup_path, dummy):
            raise RuntimeError("OpenCV could not write the warm-up image")

        # enforce_detection=False is intentional only for the synthetic warm-up
        # image. Real /detect requests use enforce_detection=True.
        DeepFace.represent(
            img_path=warmup_path,
            model_name=MODEL_NAME,
            detector_backend=DETECTOR_BACKEND,
            enforce_detection=False,
            align=True,
        )
    except Exception as exc:
        print(f"[face-service] Warm-up warning (non-fatal): {exc}", flush=True)
    finally:
        _safe_unlink(warmup_path)

    print("[face-service] Ready.", flush=True)
    yield


app = FastAPI(title="gallery-face-service", version="2.0", lifespan=lifespan)


@app.get("/health")
def health():
    tensorflow_ready = isinstance(_accelerators.get("tensorflow"), list)
    nudenet_ready = isinstance(_accelerators.get("nudenet"), list)
    ready = DeepFace is not None and tensorflow_ready and nudenet_ready

    return {
        "status": "ok" if ready else "starting",
        "ready": ready,
        "model": MODEL_NAME,
        "detector": DETECTOR_BACKEND,
        "minConfidence": MIN_CONFIDENCE,
        "gpuRequired": AI_REQUIRE_GPU,
        "gpuMemoryLimitMb": int(os.getenv("DEEPFACE_GPU_MEMORY_LIMIT_MB", "2048")),
        "accelerators": _accelerators,
        "versions": _runtime_versions,
    }


@app.post("/nsfw/detect")
async def detect_nsfw(file: UploadFile = File(...)):
    request_started = time.perf_counter()
    tmp_path = None

    try:
        tmp_path, _, _ = await _save_bounded_upload(file, {".jpg", ".jpeg", ".png", ".webp"})
        upload_read_ms = (time.perf_counter() - request_started) * 1000
        tempfile_write_ms = upload_read_ms

        model_started = time.perf_counter()
        detector = get_nsfw_detector()
        model_init_ms = (time.perf_counter() - model_started) * 1000

        inference_started = time.perf_counter()
        async with _inference_slots:
            detections = await run_in_threadpool(detector.detect, tmp_path)
        inference_ms = (time.perf_counter() - inference_started) * 1000

        scoring_started = time.perf_counter()
        explicit_detections = []
        for detection in detections:
            name = str(detection.get("class", ""))
            if name not in EXPLICIT_CLASSES:
                continue

            explicit_detections.append(
                {
                    "name": name,
                    "score": float(detection.get("score", 0.0)),
                }
            )

        explicit_detections.sort(key=lambda item: item["score"], reverse=True)
        score = max((item["score"] for item in explicit_detections), default=0.0)

        if score >= NSFW_EXPLICIT_THRESHOLD:
            level = "EXPLICIT"
        elif score >= NSFW_REVIEW_THRESHOLD:
            level = "REVIEW"
        else:
            level = "SAFE"

        scoring_ms = (time.perf_counter() - scoring_started) * 1000
        total_ms = (time.perf_counter() - request_started) * 1000
        profile = {
            "uploadReadMs": round(upload_read_ms, 2),
            "tempfileWriteMs": round(tempfile_write_ms, 2),
            "modelInitMs": round(model_init_ms, 2),
            "inferenceMs": round(inference_ms, 2),
            "scoringMs": round(scoring_ms, 2),
            "totalMs": round(total_ms, 2),
        }
        print(f"[face-service] NSFW profile {file.filename}: {profile}", flush=True)

        return JSONResponse(
            content={
                "score": score,
                "level": level,
                "labels": explicit_detections,
                "scoringVersion": NSFW_SCORING_VERSION,
                "profile": profile,
            }
        )
    except ValueError:
        return JSONResponse(
            status_code=413,
            content={"error": "Upload exceeds the configured size limit", "score": 0.0,
                     "level": "UNKNOWN", "labels": [], "scoringVersion": NSFW_SCORING_VERSION},
        )
    except Exception as exc:
        print(f"[face-service] NSFW detection failed: {exc}", flush=True)
        return JSONResponse(
            status_code=500,
            content={
                "error": "NSFW inference failed",
                "score": 0.0,
                "level": "UNKNOWN",
                "labels": [],
                "scoringVersion": NSFW_SCORING_VERSION,
            },
        )
    finally:
        _safe_unlink(tmp_path)


_DETECT_ALLOWED_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
_MAGIC_BYTES = {
    b"\xff\xd8\xff",          # JPEG
    b"\x89PNG",               # PNG
    b"GIF8",                  # GIF87a / GIF89a
    b"BM",                    # BMP
}


def _is_allowed_image(contents: bytes, suffix: str) -> bool:
    if suffix not in _DETECT_ALLOWED_SUFFIXES:
        return False
    # WebP: RIFF....WEBP
    if len(contents) >= 12 and contents[:4] == b"RIFF" and contents[8:12] == b"WEBP":
        return True
    return any(contents[: len(magic)] == magic for magic in _MAGIC_BYTES)


@app.post("/detect")
async def detect_faces(file: UploadFile = File(...)):
    """
    Detect faces in the uploaded image.

    Returns a JSON object containing zero or more detected faces. Each face has
    a bounding box, detector confidence, normalised 512-D ArcFace embedding and
    a base64-encoded JPEG crop.
    """
    tmp_path = None

    try:
        tmp_path, supplied_suffix, header = await _save_bounded_upload(file, _DETECT_ALLOWED_SUFFIXES)
        if not _is_allowed_image(header, supplied_suffix):
            return JSONResponse(
                status_code=400,
                content={"error": "Unsupported file type", "faces": []},
            )

        # Validate that OpenCV can decode the upload before running the models.
        img = await run_in_threadpool(cv2.imread, tmp_path)
        if img is None:
            return JSONResponse(
                status_code=400,
                content={"error": "Uploaded file is not a decodable image", "faces": []},
            )
        if int(img.shape[0]) * int(img.shape[1]) > MAX_IMAGE_PIXELS:
            return JSONResponse(
                status_code=413,
                content={"error": "Image dimensions exceed the configured pixel limit", "faces": []},
            )

        try:
            # Unlike the old service, real gallery images require an actual face
            # detection. This prevents DeepFace from embedding the whole image when
            # no face is present.
            async with _inference_slots:
                results = await run_in_threadpool(
                    lambda: DeepFace.represent(
                        img_path=tmp_path,
                        model_name=MODEL_NAME,
                        detector_backend=DETECTOR_BACKEND,
                        enforce_detection=True,
                        align=True,
                    )
                )
        except ValueError as exc:
            # DeepFace raises ValueError when no face can be detected. For a gallery
            # index that is a normal result, not a server error.
            message = str(exc).lower()
            if "face" in message and (
                "could not be detected" in message
                or "cannot be detected" in message
                or "face could not" in message
            ):
                return JSONResponse(content={"faces": []})
            raise

        h_img, w_img = img.shape[:2]
        faces = []

        for result in results:
            confidence = float(result.get("face_confidence", 0.0))
            if confidence < MIN_CONFIDENCE:
                continue

            area = result.get("facial_area", {})
            x = int(area.get("x", 0))
            y = int(area.get("y", 0))
            w = int(area.get("w", 0))
            h = int(area.get("h", 0))

            if w < MIN_FACE_SIZE or h < MIN_FACE_SIZE:
                continue

            # Reject malformed/out-of-frame boxes before crop generation.
            if x >= w_img or y >= h_img or x + w <= 0 or y + h <= 0:
                continue

            pad = int(min(w, h) * FACE_CROP_PADDING)
            x1 = max(0, x - pad)
            y1 = max(0, y - pad)
            x2 = min(w_img, x + w + pad)
            y2 = min(h_img, y + h + pad)

            crop = img[y1:y2, x1:x2]
            if crop.size == 0:
                continue

            ok, encoded = cv2.imencode(
                ".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, JPEG_QUALITY]
            )
            crop_b64 = (
                base64.b64encode(encoded.tobytes()).decode("ascii") if ok else ""
            )

            embedding = result.get("embedding", [])
            arr = np.asarray(embedding, dtype=np.float32)
            if arr.ndim != 1 or arr.size == 0 or not np.all(np.isfinite(arr)):
                continue

            norm = float(np.linalg.norm(arr))
            if norm <= 0.0:
                continue
            arr /= norm

            faces.append(
                {
                    "bbox": {"x": x, "y": y, "w": w, "h": h},
                    "confidence": confidence,
                    "embedding": arr.tolist(),
                    "crop_b64": crop_b64,
                }
            )

        return JSONResponse(content={"faces": faces})

    except ValueError:
        return JSONResponse(status_code=413, content={"error": "Upload exceeds the configured size limit", "faces": []})
    except Exception as exc:
        print(f"[face-service] Face detection failed for {file.filename}: {exc}", flush=True)
        return JSONResponse(
            status_code=500,
            content={"error": "Face inference failed", "faces": []},
        )
    finally:
        _safe_unlink(tmp_path)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8082, log_level="info")
