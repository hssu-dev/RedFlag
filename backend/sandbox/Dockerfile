# RedFlag sandbox browser. Build context: backend/ (compose sets it).
# Official Playwright Python image; tag pinned to playwright 1.64.0 (PyPI 2026-10-13,
# older than the 2026-10-21 cutoff; 1.64.1 = 2026-10-22 is excluded).
FROM mcr.microsoft.com/playwright/python:v1.64.0-noble

# The image ships browsers for exactly this playwright version; pin the Python package too.
RUN python -m pip install --no-cache-dir --break-system-packages "playwright==1.64.0" \
 && python -c "import importlib.metadata as m; v=m.version('playwright'); assert v=='1.64.0', v"

WORKDIR /app
COPY redflag_backend/psl.py /app/psl.py
COPY sandbox/server.py /app/server.py

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    REDFLAG_CONTRACTS_DIR=/contracts \
    REDFLAG_PSL_PATH=/contracts/public_suffix_list.dat \
    SANDBOX_PORT=8090

# Non-root: pwuser is created by the Playwright image.
USER pwuser
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/healthz',timeout=4).status==200 else 1)"
CMD ["python", "/app/server.py"]
