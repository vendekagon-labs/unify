FROM amazoncorretto:17

RUN yum install -y unzip && yum clean all

ARG DATOMIC_VERSION=1.0.7075

WORKDIR /opt/
RUN curl -O https://datomic-pro-downloads.s3.amazonaws.com/${DATOMIC_VERSION}/datomic-pro-${DATOMIC_VERSION}.zip
RUN unzip datomic-pro-${DATOMIC_VERSION}.zip
RUN rm datomic-pro-${DATOMIC_VERSION}.zip
RUN mv datomic-pro-${DATOMIC_VERSION} datomic

ENV JVM_OPTS="-Xmx1g -Xms1g"

ENTRYPOINT /opt/datomic/bin/transactor ${JVM_OPTS} /transactor.properties
