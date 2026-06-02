#!/bin/sh

##############################################################################
## Gradle start up script
##############################################################################

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine Java command
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" -cp "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
