#!/bin/sh

SBT_OPTS="-Xms512M -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256M"
# override repos listed in sbt-launch.jar
java $SBT_OPTS -Dhttps.protocols=TLSv1.2 -Dsbt.override.build.repos=true -jar `dirname $0`/sbt-launch.jar "$@"
