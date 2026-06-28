FROM python:3.12-slim

WORKDIR /app

# Abhaengigkeiten zuerst (besseres Docker-Layer-Caching)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py modi.py ./

# Lauscht intern auf 8000; Veroeffentlichung regelt docker-compose + Tailscale.
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health').status==200 else 1)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
