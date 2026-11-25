# The Forge web server, as deploy.sh stages it: the server jar and the part of Forge's res
# folder the server uses. Saved shared decks live on the volume mounted at /data.
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY res /app/res
COPY forge-web.jar /app/forge-web.jar

EXPOSE 8080
# The host password comes from the FORGE_WEB_PASSWORD secret, the password for the whole site from
# FORGE_SITE_PASSWORD (both optional).
CMD ["java", "-XX:MaxRAMPercentage=75", "-Djava.awt.headless=true", \
     "-Dforge.web.assets=/app/", "-Dforge.web.data=/data", \
     "-jar", "/app/forge-web.jar", "8080"]
