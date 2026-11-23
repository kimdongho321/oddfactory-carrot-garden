#!/bin/sh
# 테스트용 빈 앱(t1~t6)을 빌드 결과에서 앱 assets 로 복사. 먼저: android/gradlew :testapp:assembleDebug
cd "$(dirname "$0")/../android" || exit 1
mkdir -p app/src/main/assets/testapks
for i in 1 2 3 4 5 6; do cp "testapp/build/outputs/apk/t$i/debug/testapp-t$i-debug.apk" "app/src/main/assets/testapks/t$i.apk"; done
ls -la app/src/main/assets/testapks
