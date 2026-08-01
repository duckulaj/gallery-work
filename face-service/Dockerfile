FROM tensorflow/tensorflow:2.16.1-gpu

# System dependencies for OpenCV
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
# blinker is pre-installed as a distutils package in the TF image; reinstall it
# via pip first so the subsequent install can overwrite it cleanly.
RUN pip install --no-cache-dir --ignore-installed blinker && \
    pip install --no-cache-dir -r requirements.txt

COPY main.py .

# DeepFace model weights are downloaded on first use and cached in /root/.deepface
# Mount a named volume there (see docker-compose.yml) so they survive rebuilds.

EXPOSE 8082
CMD ["python", "main.py"]