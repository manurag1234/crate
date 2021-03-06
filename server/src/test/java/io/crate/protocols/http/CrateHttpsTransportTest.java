/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.protocols.http;

import static io.crate.protocols.ssl.SslConfigurationTest.getAbsoluteFilePathFromClassPath;
import static org.elasticsearch.env.Environment.PATH_HOME_SETTING;
import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.Security;
import java.util.Collections;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.netty4.Netty4HttpServerTransport;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import io.crate.netty.EventLoopGroups;
import io.crate.plugin.PipelineRegistry;
import io.crate.protocols.ssl.SslConfigSettings;
import io.crate.protocols.ssl.SslContextProviderImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandler;

public class CrateHttpsTransportTest extends ESTestCase {

    private static File trustStoreFile;
    private static File keyStoreFile;
    private static String defaultKeyStoreType = KeyStore.getDefaultType();

    @BeforeClass
    public static void beforeTests() throws IOException {
        trustStoreFile = getAbsoluteFilePathFromClassPath("truststore.jks");
        keyStoreFile = getAbsoluteFilePathFromClassPath("keystore.jks");
        Security.setProperty("keystore.type", "jks");
    }

    @AfterClass
    public static void resetKeyStoreType() {
        Security.setProperty("keystore.type", defaultKeyStoreType);
    }

    @Test
    public void testPipelineConfiguration() throws Exception {
        Settings settings = Settings.builder()
            .put(PATH_HOME_SETTING.getKey(), "/tmp")
            .put(SslConfigSettings.SSL_HTTP_ENABLED.getKey(), true)
            .put(SslConfigSettings.SSL_TRUSTSTORE_FILEPATH.getKey(), trustStoreFile.getAbsolutePath())
            .put(SslConfigSettings.SSL_TRUSTSTORE_PASSWORD.getKey(), "truststorePassword")
            .put(SslConfigSettings.SSL_KEYSTORE_FILEPATH.getKey(), keyStoreFile.getAbsolutePath())
            .put(SslConfigSettings.SSL_KEYSTORE_PASSWORD.getKey(), "keystorePassword")
            .put(SslConfigSettings.SSL_KEYSTORE_KEY_PASSWORD.getKey(), "serverKeyPassword")
            .build();

        NetworkService networkService = new NetworkService(Collections.singletonList(new NetworkService.CustomNameResolver() {
            @Override
            public InetAddress[] resolveDefault() {
                return new InetAddress[] { InetAddresses.forString("127.0.0.1") };
            }

            @Override
            public InetAddress[] resolveIfPossible(String value) throws IOException {
                return new InetAddress[] { InetAddresses.forString("127.0.0.1") };
            }
        }));

        PipelineRegistry pipelineRegistry = new PipelineRegistry(settings);
        pipelineRegistry.setSslContextProvider(new SslContextProviderImpl(settings));

        Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
            settings,
            networkService,
            BigArrays.NON_RECYCLING_INSTANCE,
            mock(ThreadPool.class),
            NamedXContentRegistry.EMPTY,
            pipelineRegistry,
            new EventLoopGroups(),
            mock(NodeClient.class));

        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            transport.start();

            Netty4HttpServerTransport.HttpChannelHandler httpChannelHandler =
                (Netty4HttpServerTransport.HttpChannelHandler) transport.configureServerChannelHandler();

            httpChannelHandler.initChannel(channel);

            assertThat(channel.pipeline().first(), instanceOf(SslHandler.class));

        } finally {
            transport.stop();
            transport.close();
            channel.releaseInbound();
            channel.close().awaitUninterruptibly();
        }
    }
}
