# syntax=docker/dockerfile:1
FROM python:3.14-alpine
COPY server.py /srv/server.py
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["python3", "-u", "/srv/server.py"]
