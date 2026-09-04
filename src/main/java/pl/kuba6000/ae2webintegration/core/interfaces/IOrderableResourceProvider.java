package pl.kuba6000.ae2webintegration.core.interfaces;

import pl.kuba6000.ae2webintegration.core.identity.OrderableResources;

/** Native grid/service capability for precise current ordering, distinct from storage listing. */
public interface IOrderableResourceProvider {

    OrderableResources web$getOrderableResources();
}
