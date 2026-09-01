FROM tensorflow/tensorflow:2.16.1-gpu

# Keep Python package output deterministic and make application logs appear
# immediately in Docker logs.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TF_USE_LEGACY_KERAS=1

# System dependencies required by OpenCV.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .

# blinker is pre-installed as a distutils package in the TensorFlow image;
# reinstall it via pip first so the requirements install can manage it cleanly.
#
# NudeNet can install the CPU onnxruntime package. Remove that package and then
# install the CUDA 12 build explicitly so CUDAExecutionProvider remains available.
# `pip check` is skipped: onnxruntime-gpu intentionally replaces the onnxruntime
# package NudeNet declares, so pip's dependency metadata will always flag it.
RUN python -m pip install --no-cache-dir --ignore-installed blinker && \
    python -m pip install --no-cache-dir -r requirements.txt && \
    python -m pip uninstall -y onnxruntime onnxruntime-gpu && \
    python -m pip install --no-cache-dir onnxruntime-gpu==1.18.0 \
        --index-url https://aiinfra.pkgs.visualstudio.com/PublicPackages/_packaging/onnxruntime-cuda-12/pypi/simple/

COPY main.py .

RUN useradd --create-home --uid 10001 gallery && chown -R gallery:gallery /app
USER gallery

# DeepFace model weights are downloaded on first use and cached in the service
# account's home directory. Mount a named volume here for persistence.
VOLUME ["/home/gallery/.deepface"]

EXPOSE 8082

# The service pre-warms ArcFace/RetinaFace during application startup.
CMD ["python", "main.py"]
