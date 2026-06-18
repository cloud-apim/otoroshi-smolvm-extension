FROM node:22-alpine
# v2 node runtime: the micro-VM must stay alive so the SmolMachine plugin can
# `exec` node / npm / npx per request. node:22-alpine's default CMD does not keep
# PID 1 running reliably, so we bake a sleeper (CMD cannot be set at machine creation).
CMD ["sleep", "infinity"]
