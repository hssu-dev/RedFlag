# RedFlag Cloud API. Build context: backend/.
# python 3.12.14-slim-bookworm (2026-09-19, older than the 2026-10-21 cutoff).
FROM python:3.12.14-slim-bookworm

ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /app
# Exact versions + hashes exported from uv.lock: `uv export --frozen --no-dev --no-emit-project`.
COPY requirements.lock.txt /app/requirements.lock.txt
RUN pip install --require-hashes -r /app/requirements.lock.txt

COPY redflag_backend /app/redflag_backend
RUN useradd --create-home --uid 10001 redflag && mkdir -p /data && chown redflag /data
USER redflag

ENV REDFLAG_CONTRACTS_DIR=/contracts CACHE_PATH=/data/redflag.sqlite3
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz',timeout=4).status==200 else 1)"
CMD ["uvicorn", "redflag_backend.app:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers"]
