package com.hedvig.paymentservice.query.trustlyOrder.enteties;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TrustlyOrderRepository extends CrudRepository<TrustlyOrder, UUID> {

}