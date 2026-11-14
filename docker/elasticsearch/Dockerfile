FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.2@sha256:be5f49784ff5ec8a5b5d7ba17f944d9d6b10c067f596ee93e6b6cb82d2dd874c

# Published by the IK maintainers. Keep the plugin and Elasticsearch versions equal.
ARG IK_SHA512=ca1fa357b460a4a5d976ef92e0d57e07af22952ed7f4861120efdce34239447b2a9c182e159f0a902a3671284aefb01750df3947bb5cf17ff162ca92b4a69ac2
COPY --chown=elasticsearch:root elasticsearch-analysis-ik-9.4.2.zip /tmp/milan-analysis-ik-9.4.2.zip
RUN printf '%s  %s\n' "${IK_SHA512}" /tmp/milan-analysis-ik-9.4.2.zip | sha512sum --check - \
    && bin/elasticsearch-plugin install --batch file:///tmp/milan-analysis-ik-9.4.2.zip \
    && grep -Fx 'elasticsearch.version=9.4.2' plugins/analysis-ik/plugin-descriptor.properties
