package dev.lukasgrigis.blog.amqp.dispatcher.rest;

import jakarta.validation.constraints.NotBlank;

record JobRequest(@NotBlank String payload) {

}
