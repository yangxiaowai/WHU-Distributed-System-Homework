#!/bin/sh
set -eu

echo "[replica-init] waiting for mysql-primary..."
until mysqladmin ping -hmysql-primary -uroot -proot --silent; do
  sleep 2
done

echo "[replica-init] waiting for local mysql..."
until mysqladmin ping -hlocalhost -uroot -proot --silent; do
  sleep 2
done

echo "[replica-init] configuring replication (GTID auto-position)..."
mysql -uroot -proot -e "
  STOP REPLICA;
  RESET REPLICA ALL;
  CHANGE REPLICATION SOURCE TO
    SOURCE_HOST='mysql-primary',
    SOURCE_USER='repl',
    SOURCE_PASSWORD='replpass',
    SOURCE_AUTO_POSITION=1,
    GET_SOURCE_PUBLIC_KEY=1;
  START REPLICA;
"

echo "[replica-init] replication status:"
mysql -uroot -proot -e "SHOW REPLICA STATUS\G" | sed -n '1,120p' || true

