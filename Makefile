JAR = target/cartographer-1.0-SNAPSHOT.jar

.PHONY: run package clean

run: package
	java -jar $(JAR)

package:
	mvn package

clean:
	mvn clean
