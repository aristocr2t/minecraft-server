#/bin/bash
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

echo "$(bash_ts) update ${SERVER_HOST}"

docker exec -it ${SERVER_HOST} bash -c "
rm -rf /tmp/server

mkdir -p /tmp/server
cd /tmp/server

wget -qO BuildTools.jar https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar
java -jar BuildTools.jar --rev ${SERVER_VERSION}

rm -rf /tmp/server
"
