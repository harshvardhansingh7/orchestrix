package com.orchestrix.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    @NotEmpty
    @Valid
    private List<ChatMessage> messages;

    /** When true, response is delivered as Server-Sent Events. */
    private boolean stream;

    /** Optional ceiling on completion tokens. Defaults applied downstream. */
    private Integer maxTokens;

    /** Optional temperature override. */
    private Double temperature;

    /** Hint to the routing engine — never a hard provider selection. */
    private String preferredTier;
}
