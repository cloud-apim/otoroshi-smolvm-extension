FROM node:20-alpine
WORKDIR /app
COPY server.js .
ENV PORT=8080
EXPOSE 8080
# The image's CMD is the long-running server; the micro-VM runs it on boot.
CMD ["node", "/app/server.js"]
