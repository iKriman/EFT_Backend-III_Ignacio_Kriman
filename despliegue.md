# Despliegue de Banco XYZ en nube AWS

## Objetivo

Desplegar la solución en AWS manteniendo contenedores, escalabilidad horizontal, configuración segura, descubrimiento/servicios y mensajería administrada cuando corresponda.

## Arquitectura propuesta

- **Amazon ECR**: repositorio de imágenes Docker.
- **Amazon ECS con Fargate**: ejecución de microservicios y BFF sin administrar servidores.
- **Application Load Balancer**: entrada HTTPS para BFF Web, Móvil y ATM.
- **AWS Certificate Manager**: certificados TLS.
- **AWS Secrets Manager / Parameter Store**: secretos OAuth2 y configuraciones sensibles.
- **Amazon MSK**: Kafka administrado para producción.
- **CloudWatch Logs/Metrics**: observabilidad.
- **Auto Scaling de ECS**: escalabilidad horizontal por CPU, memoria o número de solicitudes.
- **VPC con subredes privadas**: microservicios internos sin exposición pública.

## 1. Crear repositorios ECR

Crear repositorios para:

- config-server
- discovery-server
- auth-server
- batch-service
- account-service
- payment-service
- customer-service
- bff-web
- bff-mobile
- bff-atm
- notification-service

## 2. Construir imágenes

Ejemplo:

```bash
docker build --build-arg MODULE=account-service -t banco-xyz/account-service:final .
docker build --build-arg MODULE=payment-service -t banco-xyz/payment-service:final .
docker build --build-arg MODULE=customer-service -t banco-xyz/customer-service:final .
```

Repetir para el resto de módulos.

## 3. Publicar en ECR

Autenticarse con AWS CLI:

```bash
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com
```

Etiquetar y publicar cada imagen:

```bash
docker tag banco-xyz/account-service:final <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/account-service:final
docker push <ACCOUNT_ID>.dkr.ecr.us-east-1.amazonaws.com/account-service:final
```

## 4. Red

1. Crear VPC.
2. BFF y ALB pueden usar subredes públicas/privadas según diseño.
3. Microservicios de negocio, Kafka/MSK y componentes internos deben quedar en subredes privadas.
4. Security Groups deben permitir solo tráfico necesario entre servicios.

## 5. Kafka administrado

En producción se recomienda Amazon MSK. Crear cluster y reemplazar `KAFKA_BOOTSTRAP_SERVERS` por los brokers privados entregados por MSK.

## 6. Secrets

Guardar `API_CLIENT_SECRET` y `ATM_CLIENT_SECRET` en AWS Secrets Manager. Las task definitions de ECS deben inyectarlos como secretos, nunca dejarlos en la imagen ni en Git.

## 7. ECS Fargate

Crear una Task Definition por servicio. Configurar:

- imagen ECR;
- CPU/memoria;
- variables `CONFIG_SERVER_URL`, `EUREKA_URL`, `OAUTH_ISSUER`, `OAUTH_JWKS_URI`, `KAFKA_BOOTSTRAP_SERVERS`;
- secretos OAuth2;
- CloudWatch Logs;
- health checks.

## 8. Servicios y escalabilidad

Crear ECS Services para `account-service`, `payment-service` y `customer-service` con al menos 2 tareas deseadas en producción.

Configurar Application Auto Scaling, por ejemplo:

- mínimo: 2 tareas;
- máximo: 6 tareas;
- target tracking CPU: 60%;
- target tracking memoria: 70%.

Esto cumple la escalabilidad horizontal: nuevas instancias se agregan sin modificar el código.

## 9. HTTPS

1. Solicitar certificado en ACM.
2. Asociar certificado al listener HTTPS 443 del ALB.
3. Redirigir HTTP 80 a HTTPS 443.
4. Publicar únicamente los BFF necesarios.
5. Mantener microservicios internos sin IP pública.

## 10. Observabilidad

- CloudWatch Logs por servicio.
- Alarmas por 5xx, CPU alta, memoria alta y reinicios.
- Actuator `/actuator/health` para health checks.
- Métricas de Resilience4j para circuit breakers.
- Métricas de lag de consumidores Kafka/MSK.

## 11. Estrategia de despliegue

1. Config Server / configuración.
2. Eureka o service discovery equivalente.
3. Authorization Server.
4. Kafka/MSK.
5. Microservicios de negocio.
6. Consumidores de eventos.
7. BFF.
8. Batch service.
9. Pruebas smoke y de seguridad.

## 12. Validación final

- Todos los servicios saludables.
- JWT válido y scopes correctos.
- BFF Web/Móvil/ATM responden payload específico.
- Kafka publica y consume eventos.
- Circuit breaker funciona ante caída simulada.
- Batch genera los tres resultados.
- Auto Scaling aumenta/reduce tareas según métrica.
- Todo tráfico público usa HTTPS.
