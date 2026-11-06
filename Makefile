JAR = cartographer-cli/target/cartographer-cli-1.0-SNAPSHOT.jar
SERVER_JAR = cartographer-server/target/cartographer-server-1.0-SNAPSHOT.jar

.PHONY: run run-server package clean

run: package
	java -jar $(JAR)

run-server: package
	java -jar $(SERVER_JAR)

package:
	mvn package

clean:
	mvn clean
