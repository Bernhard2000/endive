# arm name | java | classes/cp arm | extra JVM args
J17=/home/bernhard/Dokumente/MasterThesis/Endive/fork/jdks/jdk-17.0.21+9/bin/java
J27=/home/bernhard/Dokumente/MasterThesis/Endive/fork/jdks/jdk-27.0.1.0.2+1/bin/java
ARMS=(
  "up17|$J17|up|"
  "fork17|$J17|fork|"
  "fork17noWA|$J17|fork|-Dendive.memCopyWorkaround=false -Dendive.compiler.unsignedCompareFusion=false"
  "up27|$J27|up|"
  "fork27|$J27|fork|"
)
