# Como desplegar el servicio

Para desplegar el servicio se necesita Docker Desktop con el motor iniciado. En Windows,
ademas hay que copiar wslconfig.example a .wslconfig para darle memoria a WSL2.

1. Copia .env.example a .env.
2. Corre docker compose up -d. El make up detecta la GPU sola.

Si la maquina tiene una GPU NVIDIA, docker compose usa automaticamente compose.gpu.yml
para reservarla.
