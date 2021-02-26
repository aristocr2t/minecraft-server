#!/bin/bash
cd $(dirname $(readlink -f "$0"))

ENV=${ENV:-production}

if [ -f ".env/$ENV.env" ]; then
	export $(cat .env/$ENV.env | xargs)
else
	if [ -z $ENV ]; then
		echo "Environment ENV not set"
	else
		echo "File .env/$ENV.env not found"
	fi
	exit 1
fi

docker exec -it "$SERVER_HOST" bash -c './mcrcon -p mcrcon'
