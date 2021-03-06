/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.spring.integration.storage.queue;

import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.QueueMessageItem;
import com.azure.storage.queue.models.QueueStorageException;
import com.microsoft.azure.spring.integration.core.AzureHeaders;
import com.microsoft.azure.spring.integration.core.api.CheckpointMode;
import com.microsoft.azure.spring.integration.core.api.PartitionSupplier;
import com.microsoft.azure.spring.integration.core.api.reactor.AzureCheckpointer;
import com.microsoft.azure.spring.integration.core.api.reactor.Checkpointer;
import com.microsoft.azure.spring.integration.storage.queue.converter.StorageQueueMessageConverter;
import com.microsoft.azure.spring.integration.storage.queue.factory.StorageQueueClientFactory;
import com.microsoft.azure.spring.integration.storage.queue.util.StorageQueueHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class StorageQueueTemplate implements StorageQueueOperation {
    private static final Logger log = LoggerFactory.getLogger(StorageQueueTemplate.class);
    private static final int DEFAULT_VISIBILITY_TIMEOUT_IN_SECONDS = 30;
    private static final String MSG_FAIL_CHECKPOINT = "Failed to checkpoint %s in storage queue '%s'";
    private static final String MSG_SUCCESS_CHECKPOINT = "Checkpointed %s in storage queue '%s' in %s mode";
    private final StorageQueueClientFactory storageQueueClientFactory;

    protected StorageQueueMessageConverter messageConverter = new StorageQueueMessageConverter();

    private int visibilityTimeoutInSeconds = DEFAULT_VISIBILITY_TIMEOUT_IN_SECONDS;

    private Class<?> messagePayloadType = byte[].class;

    private CheckpointMode checkpointMode = CheckpointMode.RECORD;

    public StorageQueueTemplate(@NonNull StorageQueueClientFactory storageQueueClientFactory) {
        this.storageQueueClientFactory = storageQueueClientFactory;
        log.info("StorageQueueTemplate started with properties {}", buildProperties());
    }

    @Override
    public <T> Mono<Void> sendAsync(String queueName, @NonNull Message<T> message,
                                    PartitionSupplier partitionSupplier) {
        Assert.hasText(queueName, "queueName can't be null or empty");
        QueueMessageItem queueMessageItem = messageConverter.fromMessage(message, QueueMessageItem.class);
        QueueAsyncClient queueClient = storageQueueClientFactory.getOrCreateQueueClient(queueName);

        return queueClient.sendMessage(queueMessageItem.getMessageText()).then();
    }

    @Override
    public Mono<Message<?>> receiveAsync(String queueName) {
        return this.receiveAsync(queueName, visibilityTimeoutInSeconds);
    }

    @Override
    public void setCheckpointMode(CheckpointMode checkpointMode) {
        Assert.state(isValidCheckpointMode(checkpointMode),
                "Only MANUAL or RECORD checkpoint mode is supported in StorageQueueTemplate");
        this.checkpointMode = checkpointMode;
        log.info("StorageQueueTemplate checkpoint mode becomes: {}", this.checkpointMode);
    }

    @Override
    public void setMessagePayloadType(Class<?> payloadType) {
        this.messagePayloadType = payloadType;
        log.info("StorageQueueTemplate messagePayloadType becomes: {}", this.messagePayloadType);
    }

    @Override
    public void setVisibilityTimeoutInSeconds(int timeout) {
        Assert.state(timeout > 0, "VisibilityTimeoutInSeconds should be positive");
        this.visibilityTimeoutInSeconds = timeout;
        log.info("StorageQueueTemplate VisibilityTimeoutInSeconds becomes: {}", this.visibilityTimeoutInSeconds);
    }

    private Mono<Message<?>> receiveAsync(String queueName, int visibilityTimeoutInSeconds) {
        Assert.hasText(queueName, "queueName can't be null or empty");


        QueueAsyncClient queueClient = storageQueueClientFactory.getOrCreateQueueClient(queueName);


        return queueClient.receiveMessages(1, Duration.ofSeconds(visibilityTimeoutInSeconds))
                .onErrorMap(QueueStorageException.class, e ->
                        new StorageQueueRuntimeException("Failed to send message to storage queue", e))
                .next()
                .map(messageItem -> {

                    Map<String, Object> headers = new HashMap<>();
                    Checkpointer checkpointer = new AzureCheckpointer(() -> checkpoint(queueClient, messageItem));

                    if (checkpointMode == CheckpointMode.RECORD) {
                        checkpointer.success().subscribe();
                    } else if (checkpointMode == CheckpointMode.MANUAL) {
                        headers.put(AzureHeaders.CHECKPOINTER, checkpointer);
                    }

                    return messageConverter.toMessage(messageItem, new MessageHeaders(headers), messagePayloadType);
                });


    }

    private Mono<Void> checkpoint(QueueAsyncClient queueClient, QueueMessageItem messageItem) {
        return queueClient
                .deleteMessage(messageItem.getMessageId(), messageItem.getPopReceipt())
                .doOnSuccess(v -> {
                    if (log.isDebugEnabled()) {
                        log.debug(buildCheckpointSuccessMessage(messageItem, queueClient.getQueueName()));
                    }
                })
                .doOnError(t -> {
                    if (log.isWarnEnabled()) {
                        log.warn(buildCheckpointFailMessage(messageItem, queueClient.getQueueName()), t);
                    }
                });
    }

    private Map<String, Object> buildProperties() {
        Map<String, Object> properties = new HashMap<>();

        properties.put("visibilityTimeout", this.visibilityTimeoutInSeconds);
        properties.put("messagePayloadType", this.messagePayloadType);
        properties.put("checkpointMode", this.checkpointMode);

        return properties;
    }

    private boolean isValidCheckpointMode(CheckpointMode checkpointMode) {
        return checkpointMode == CheckpointMode.MANUAL || checkpointMode == CheckpointMode.RECORD;
    }

    private void checkpointHandler(QueueMessageItem message, String queueName, Throwable t) {
        if (t != null) {
            if (log.isWarnEnabled()) {
                log.warn(buildCheckpointFailMessage(message, queueName), t);
            }
        } else if (log.isDebugEnabled()) {
            log.debug(buildCheckpointSuccessMessage(message, queueName));
        }
    }

    private String buildCheckpointFailMessage(QueueMessageItem cloudQueueMessage, String queueName) {
        return String.format(MSG_FAIL_CHECKPOINT, StorageQueueHelper.toString(cloudQueueMessage), queueName);
    }

    private String buildCheckpointSuccessMessage(QueueMessageItem cloudQueueMessage, String queueName) {
        return String.format(MSG_SUCCESS_CHECKPOINT, StorageQueueHelper.toString(cloudQueueMessage), queueName,
                checkpointMode);
    }

    public StorageQueueMessageConverter getMessageConverter() {
        return messageConverter;
    }

    public void setMessageConverter(StorageQueueMessageConverter messageConverter) {
        this.messageConverter = messageConverter;
    }

    public int getVisibilityTimeoutInSeconds() {
        return visibilityTimeoutInSeconds;
    }

    public Class<?> getMessagePayloadType() {
        return messagePayloadType;
    }

    public CheckpointMode getCheckpointMode() {
        return checkpointMode;
    }
}
