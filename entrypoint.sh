#!/bin/sh
set -eu

exec java \
  -cp 'core/target/classes:example/target/classes:example/target/dependency/*' \
  com.volcengine.veadk.example.AgentKitWeb \
  --adk.agents.source-dir=example/target \
  --server.address=0.0.0.0 \
  --server.port=8000
