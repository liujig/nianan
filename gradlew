#!/bin/bash
# Gradle wrapper — 启动 gradle-wrapper.jar
exec java -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
