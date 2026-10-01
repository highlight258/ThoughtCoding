@echo off
setlocal
set "JAR_PATH=%~dp0target\thoughtcoding.jar"
java -jar "%JAR_PATH%" %*
