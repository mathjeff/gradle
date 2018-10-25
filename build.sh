set -e
cd "$(dirname $0)"

./gradlew install -P java9Home=/usr/local/google/workspace/android-master/prebuilts/jdk/jdk9/linux-x86 -Pgradle_installPath=installed
