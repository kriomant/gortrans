#!/bin/sh

SBT_OPTS="-Xms512M -Xmx1G -XX:+CMSClassUnloadingEnabled"
java $SBT_OPTS -jar `dirname $0`/sbt-launch.jar "$@"
