# Base image for the CircleCI test job. Keep Java aligned with the project and
# install the software display/OpenGL runtime needed by automated Minecraft clients.
FROM cimg/openjdk:21.0

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb \
        xauth \
        mesa-utils \
        libgl1-mesa-dri \
        libglx-mesa0 \
        libopenal1 \
        libasound2 \
        libxrandr2 \
        libxcursor1 \
        libxi6 \
        libxinerama1 \
        libxxf86vm1 \
    && rm -rf /var/lib/apt/lists/*

USER circleci
