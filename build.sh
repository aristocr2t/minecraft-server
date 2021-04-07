#!/bin/bash
cd $(dirname $(readlink -f "$0"))

ENV=${ENV:-production}

if [ -f ".env/${ENV}.env" ]; then
	export $(cat .env/${ENV}.env | xargs -d '\n')
else
	if [ -z ${ENV} ]; then
		echo "Environment ENV not set"
	else
		echo "File .env/${ENV}.env not found"
	fi
	exit 1
fi

bash_ts() {
	echo -e "\e[33m$(date +'%Y-%m-%d %H:%M:%S.%3N')\e[39m"
}

if [ ! "$(docker ps -aq -f name=^${SERVER_HOST}$)" ]; then
	echo "$(bash_ts) install ${SERVER_HOST}"
	docker-compose up --detach --build server
elif [ "$(docker ps -aq -f status=exited -f name=^${SERVER_HOST}$)" ]; then
	echo "$(bash_ts) start ${SERVER_HOST}"
	docker start ${SERVER_HOST} >/dev/null
fi
