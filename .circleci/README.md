# CircleCI tests

CircleCI runs the complete JUnit suite across four Java 21 workers. It stores
JUnit XML for each worker and publishes aggregate JaCoCo coverage after verifying
that every discovered test class ran exactly once. The workflow also runs
SonarQube analysis.

Minecraft client probes are available for manual runs. The optional
[`Dockerfile`](Dockerfile) provides the virtual display and software OpenGL
dependencies for running those probes in a container.
