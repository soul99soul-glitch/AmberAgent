FROM alpine:3.20
RUN apk add --no-cache openssh-server \
 && ssh-keygen -A \
 && adduser -D -s /sbin/nologin jumper \
 && adduser -D -s /bin/sh deploy \
 && sed -i 's/^jumper:!/jumper:*/' /etc/shadow \
 && echo 'deploy:fixture-only-password' | chpasswd \
 && mkdir -p /etc/ssh/authorized_keys
EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
