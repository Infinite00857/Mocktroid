CHPR research tools (not part of the app). They print the statistics quoted in docs/CHPR-format.txt.

Build the app first (mvn -f source/pom.xml package), then from the repo root:

  javac -cp source/target/mocktroid.jar -d source/target/tools source/tools/chpr/*.java
  java -cp "source/target/mocktroid.jar:source/target/tools" ChprMap <romfs folder> <CHPR GUID> [save-as.chpr]
  java -cp "source/target/mocktroid.jar:source/target/tools" ChprTables <file.chpr> [clips-file.chpr]

(On Windows use ; instead of : in the class path.)

ChprMap     labels every byte it can explain (header, strings, name lists, node table, transforms,
            inverse binds, constant pools, clips, GUID references), prints the coverage per structure
            and the largest unexplained gaps with a hex preview.
ChprTables  takes a CHPR saved by ChprMap; compares a few clips' output addresses with the joint pose
            addresses from the hierarchy and with the typed output-address list.
