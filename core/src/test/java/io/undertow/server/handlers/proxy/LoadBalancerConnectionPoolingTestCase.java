package io.undertow.server.handlers.proxy;

import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.ProxyIgnore;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.mina.util.ConcurrentHashSet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(DefaultServer.class)
@ProxyIgnore
public class LoadBalancerConnectionPoolingTestCase {

    public static final int TTL = 2000;
    private static Undertow undertow;

    private static final Set<ServerConnection> activeConnections = new ConcurrentHashSet<>();

    static final String host = DefaultServer.getHostAddress("default");
    static int port = DefaultServer.getHostPort("default");

    @BeforeClass
    public static void before() throws Exception {

        ProxyHandler proxyHandler = new ProxyHandler(new LoadBalancingProxyClient()
                .setConnectionsPerThread(1)
                .setSoftMaxConnectionsPerThread(0)
                .setTtl(TTL)
                .addHost(new URI("http", null, host, port, null, null, null), "s1")
                , 10000, ResponseCodeHandler.HANDLE_404);

        // Default server uses 8 io threads which is hard to test against
        undertow = Undertow.builder()
                .setIoThreads(1)
                .addHttpListener(port + 1, host)
                .setHandler(proxyHandler)
                .build();
        undertow.start();

        DefaultServer.setRootHandler(new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                final ServerConnection con = exchange.getConnection();
                if(!activeConnections.contains(con)) {
                    activeConnections.add(con);
                    con.addCloseListener(new ServerConnection.CloseListener() {
                        @Override
                        public void closed(ServerConnection connection) {
                            activeConnections.remove(connection);
                        }
                    });
                }
            }
        });
    }

    @AfterClass
    public static void after() {
        undertow.stop();
    }

    @Test
    public void shouldReduceConnectionPool() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        PoolingClientConnectionManager conman = new PoolingClientConnectionManager();
        conman.setDefaultMaxPerRoute(20);
        final TestHttpClient client = new TestHttpClient(conman);
        int requests = 20;
        final CountDownLatch latch = new CountDownLatch(requests);
        long ttlStartExpire = TTL + System.currentTimeMillis();
        try {
            for (int i = 0; i < requests; ++i) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        HttpGet get = new HttpGet("http://" + host + ":" + (port + 1));
                        try {
                            client.execute(get, new ResponseHandler<HttpResponse>() {
                                @Override
                                public HttpResponse handleResponse(HttpResponse response) throws IOException {
                                    latch.countDown();
                                    Assert.assertEquals(StatusCodes.OK, response.getStatusLine().getStatusCode());
                                    return response;
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
            }
            if(!latch.await(2000, TimeUnit.MILLISECONDS)) {
                Assert.fail();
            }
        } finally {
            executorService.shutdownNow();
            client.getConnectionManager().shutdown();
        }

        if(activeConnections.size() != 1) {
            //if the test is slow this line could be hit after the expire time
            //uncommon, but we guard against it to prevent intermittent failures
            if(System.currentTimeMillis() < ttlStartExpire) {
                Assert.fail("there should still be a connection");
            }
        }
        long end = System.currentTimeMillis() + (TTL * 3);
        while (!activeConnections.isEmpty() && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        Assert.assertEquals(0, activeConnections.size());
    }
}
