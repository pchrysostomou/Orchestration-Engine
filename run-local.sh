#!/bin/bash
# Ξεκινά τον server τοπικά (χρειάζεται docker compose up db redis -d πρώτα)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
export DATABASE_URL=jdbc:postgresql://localhost:5433/workflows
export DATABASE_USER=workflow_user
export DATABASE_PASSWORD=secret
export REDIS_URL=redis://localhost:6380

./gradlew run
