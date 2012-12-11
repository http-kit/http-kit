/**
   freebsd's kqueue's performance is amazing
   On Macbook Air.

   concurrency: 1000; requests: 2000000; time: 62346ms; req/s: 32079.04; receive bytes: 35717.01M data; throughput: 572.88 M/s

   How to run it:
   Server:   c99 kqueue.c
   Client:   cd test/java && javac me/shenfeng/http/PerformanceBench.java && java me.shenfeng.http.PerformanceBench

   RAM's performance:
   dd if=/dev/zero of=/dev/null bs=8096 count=2000000  => 16192000000 bytes transferred in 3.057169 secs (5296403375 bytes/sec) => 5051M/s

   Feng Shen <shenedu@gmail.com>  2012/12/11
*/
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>             //  read, write
#include <sys/event.h>          //  kqueue
#include <arpa/inet.h>          //  htons, serveraddr, AF_INET
#include <string.h>
#include <fcntl.h>              //  fcntl

char *resp;                     //  const bufer write to client
int length;                     //  total bytes of resp

#define EVENTS_COUNT  4096
int changes = 0;
struct kevent eventlist[EVENTS_COUNT];
struct kevent changelist[EVENTS_COUNT];

#define BUFFEER_LENGTH 4096
char buffer[BUFFEER_LENGTH];              //  read buffer, shared

void make_socket_non_blokcing(int sfd) {
    int flags;
    flags = fcntl(sfd, F_GETFL, 0);
    if (flags == -1) { perror("fcntl"); exit(1); }
    flags |= O_NONBLOCK;
    if(fcntl(sfd, F_SETFL, flags) == -1) {
        perror("fcntl"); exit(EXIT_FAILURE);
    }
}

typedef struct sockaddr SA;

int open_nonb_listenfd(int port) {
    int listenfd, optval=1;
    struct sockaddr_in serveraddr;
    // Create a socket descriptor
    if ((listenfd = socket(AF_INET, SOCK_STREAM, 0)) < 0) {
        perror("ERROR");
        exit(EXIT_FAILURE);
    }
    // Eliminates "Address already in use" error from bind.
    if (setsockopt(listenfd, SOL_SOCKET, SO_REUSEADDR,
                   (const void *)&optval , sizeof(int)) < 0) {
        perror("setsockopt");
        exit(EXIT_FAILURE);
    }
    // 6 is TCP's protocol number, don't send out partial frames
    // enable this, much faster : 4000 req/s -> 17000 req/s
    // if (setsockopt(listenfd, 6, TCP_CORK,
    //                (const void *)&optval , sizeof(int)) < 0)
    //     return -1;
    /* Listenfd will be an endpoint for all requests to port
       on any IP address for this host */
    memset(&serveraddr, 0, sizeof(serveraddr));
    serveraddr.sin_family = AF_INET;
    serveraddr.sin_addr.s_addr = htonl(INADDR_ANY);
    serveraddr.sin_port = htons((unsigned short)port);
    if (bind(listenfd, (SA *)&serveraddr, sizeof(serveraddr)) < 0) {
        perror("bind");
        exit(EXIT_FAILURE);
    }
    make_socket_non_blokcing(listenfd);
    if (listen(listenfd, 10240) < 0) {
        perror("listen");
        exit(EXIT_FAILURE);
    }
    return listenfd;
}
void process_request(int sock_fd) {
    int r = read(sock_fd, buffer, BUFFEER_LENGTH);
    // printf("read from %d, %d bytes\n", sock_fd, r);
    if (r > 0) {
        buffer[r] = 0;
        EV_SET(&changelist[changes], sock_fd, EVFILT_WRITE, EV_ADD, 0, 0, NULL);
        changes += 1;
    }
}

void write_response(int sock_fd) {
    int n = write(sock_fd, resp, length);
    // printf("write to %d, %d bytes\n", sock_fd, n);
    if (n == length) {
        EV_SET(&changelist[changes], sock_fd, EVFILT_WRITE, EV_DELETE, 1024, 0, NULL);
        changes += 1;
    } else {
        printf("only write %d bytes to %d\n", n, sock_fd);
    }
}

void accept_incoming(struct kevent *event) {
    // printf("need accept: %d \n", event->data);
    int listen_sock = event->ident;
    struct sockaddr_in clientaddr;
    socklen_t clientlen = sizeof clientaddr;
    for (int i = 0; i < event->data; ++ i) {
        int conn_sock = accept(listen_sock, (SA *)&clientaddr, &clientlen);
        if (conn_sock <= 0) {
            perror("accept");
        } else {
            // printf("accept %d\n", conn_sock);
            make_socket_non_blokcing(conn_sock);
            EV_SET(&changelist[changes], conn_sock, EVFILT_READ, EV_ADD, 0, 0, NULL);
            changes += 1;
        }
    }
}

#define BODY_LENGHT 18684

void init_resp() {
    char *headers = "HTTP/1.1 200 OK\r\nContent-Length: %d\r\n\r\n";
    int t = BODY_LENGHT + strlen(headers);
    resp = malloc(t);
    sprintf(resp, headers, BODY_LENGHT);
    char *p = resp + strlen(resp);
    for (int i = 0; i < BODY_LENGHT; ++ i) {
        *p++ = 'a' + i % 26;
    }
    length = strlen(resp);
}

int main(int argc, char** argv) {
    int kfd = kqueue();
    int listen_sock = open_nonb_listenfd(9091);
    init_resp();

    printf("kqueue fd: %d, listen fd %d, listens on :9091\n", kfd, listen_sock);

    EV_SET(&changelist[0], listen_sock, EVFILT_READ, EV_ADD, 0, 0, NULL);
    int n = kevent(kfd, changelist, 1, eventlist, EVENTS_COUNT, 0);
    while(1) {
        for (int i = 0; i < n; ++ i) {
            struct kevent event = eventlist[i];
            // printf("fd: %d, filter: %d\n", event.ident, event.filter);
            // printf("%d, flags %d\n", event.ident, event.flags);

            if (event.flags & EV_ERROR) {
                printf("error fd %d, flags: %d, data: %d\n", event.ident, event.flags, event.data);
                close(event.ident);
                continue;
            }
            if (event.ident == listen_sock) {
                accept_incoming(&event);
            } else {
                if (event.flags & EV_EOF) {
                    close(event.ident);
                } else if (event.filter == EVFILT_READ) {
                    process_request(event.ident);
                } else if (event.filter == EVFILT_WRITE ) {
                    write_response(event.ident);
                }
            }
        }
        // printf("%d events, changes %d\n", n, changes);
        n = kevent(kfd, changelist, changes, eventlist, 100, 0);
        changes = 0;
    }
}
