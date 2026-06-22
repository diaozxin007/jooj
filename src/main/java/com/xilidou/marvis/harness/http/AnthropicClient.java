package com.xilidou.marvis.harness.http;

import com.xilidou.marvis.harness.http.dto.CreateMessageRequest;
import com.xilidou.marvis.harness.http.dto.CreateMessageResponse;

public interface AnthropicClient {

    CreateMessageResponse createMessage(CreateMessageRequest req);
}
