@echo off
rem 本地构建入口：固定 JDK17 与 Maven3.9.16，避免污染全局 PATH
set JAVA_HOME=C:\CodingSoftware\JDK17\jdk-17.0.17.10-hotspot
set MAVEN_HOME=C:\CodingSoftware\apache-maven-3.9.16
set PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%
call "%MAVEN_HOME%\bin\mvn.cmd" %*
