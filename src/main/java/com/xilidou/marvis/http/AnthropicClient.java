package com.xilidou.marvis.http;

import com.xilidou.marvis.http.dto.CreateMessageRequest;
import com.xilidou.marvis.http.dto.CreateMessageResponse;

public interface AnthropicClient {

    CreateMessageResponse createMessage(CreateMessageRequest req);
}
