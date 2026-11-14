# Cópia dos backups fora do servidor: criptografa com age (chave pública) e envia ao Cloudflare R2 com rclone.
FROM alpine:3.20
RUN apk add --no-cache age rclone tar ca-certificates
COPY offsite.sh /usr/local/bin/offsite.sh
RUN sed -i 's/\r$//' /usr/local/bin/offsite.sh && chmod +x /usr/local/bin/offsite.sh
ENTRYPOINT ["/usr/local/bin/offsite.sh"]
