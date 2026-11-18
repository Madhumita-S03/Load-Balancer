package com.madhumita.dispatch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** A request may contain an empty list, which is a valid no-op. */
public record OrdersRequest(@NotNull List<@NotNull @Valid OrderInput> orders) { }
