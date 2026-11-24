# PDF para gráfica isolado (Ghostscript + Python da própria distribuição). Construído a partir da raiz do booksmith,
# para usar o mesmo perfil de cor e a mesma definição de PDF/X que a API usa no notebook (src/main/resources/print).
FROM ubuntu:26.04

# APT_REFRESH muda a cada deploy (a data): esta camada não fica velha no cache e pega as correções do Ubuntu
ARG APT_REFRESH=0
RUN echo "apt: $APT_REFRESH" && apt-get update \
    && apt-get install -y --no-install-recommends ghostscript python3 \
    && rm -rf /var/lib/apt/lists/*

COPY src/main/resources/print/ISOcoated_v2_eci.icc src/main/resources/print/pdfx-1a.ps /app/print/
COPY docker/pdfx/server.py /app/server.py

RUN useradd --system --uid 10002 pdfx
USER pdfx
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=10s CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8085/health', timeout=8)"
ENTRYPOINT ["python3", "/app/server.py"]
