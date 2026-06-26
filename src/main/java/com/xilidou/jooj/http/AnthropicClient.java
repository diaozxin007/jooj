package com.xilidou.jooj.http;

import com.xilidou.jooj.http.dto.CreateMessageRequest;
import com.xilidou.jooj.http.dto.CreateMessageResponse;

public interface AnthropicClient {

    CreateMessageResponse createMessage(CreateMessageRequest req);
}
