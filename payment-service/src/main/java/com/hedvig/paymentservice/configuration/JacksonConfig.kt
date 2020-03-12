package com.hedvig.paymentservice.configuration

import com.adyen.model.checkout.DefaultPaymentMethodDetails
import com.adyen.model.checkout.PaymentMethodDetails
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleAbstractTypeResolver
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
class JacksonConfig {

  @Bean
  fun objectMapper(): ObjectMapper {
    val module = SimpleModule("PaymentMethodDetailsModule", Version.unknownVersion())

    module.setAbstractTypes(
      SimpleAbstractTypeResolver().addMapping(
        PaymentMethodDetails::class.java,
        DefaultPaymentMethodDetails::class.java
      )
    )

    return Jackson2ObjectMapperBuilder.json()
      .modules(
        module
      )
      .build()
  }
}
