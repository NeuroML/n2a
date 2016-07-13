
@echo off

Rem IMPORTANT: Assumes the working directory is the project root.
Rem Example: cd C:\Users\dtrumbo\Projects\workspaces\eclipse-3.7-primary\NeuronsToAlgorithms\N2A

SET PROJ_ROOT=%~dp0

Rem Purge generated files that might be replaced
cd "%PROJ_ROOT%src\gov\sandia\n2a\plugins\n2a\language\parse"
del TokenMgrError.java
del ParseException.java
del Token.java
del JavaCharStream.java

Rem Generate
cd "%PROJ_ROOT%src\gov\sandia\n2a\plugins\n2a\language\parse"
java -classpath "%PROJ_ROOT%lib\javacc-5.0\javacc.jar" jjtree grammar.jjt
java -classpath "%PROJ_ROOT%lib\javacc-5.0\javacc.jar" javacc grammar.jj

cd "%PROJ_ROOT%"
