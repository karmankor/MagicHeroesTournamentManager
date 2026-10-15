#!/bin/sh
APP_HOME="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -n "$JAVA_HOME" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
if ! command -v "$JAVACMD" >/dev/null 2>&1 && [ ! -x "$JAVACMD" ]; then
  echo "Java nebyla nalezena. Nainstaluj JDK 17 nebo nastav JAVA_HOME." >&2
  exit 1
fi
exec "$JAVACMD" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
