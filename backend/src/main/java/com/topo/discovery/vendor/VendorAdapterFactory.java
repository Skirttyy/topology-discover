package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selecteaza VendorAdapter-ul corect pe baza enum-ului Vendor.
 * Spring injecteaza automat toate bean-urile care implementeaza VendorAdapter
 * (JuniperAdapter, AristaAdapter, + orice altul adaugat in viitor).
 *
 * Pentru a adauga un vendor nou (ex: Cisco IOS-XR), e suficient sa creezi
 * o clasa noua @Component care implementeaza VendorAdapter - nu trebuie
 * modificat acest fisier.
 */
@Component
public class VendorAdapterFactory {

    private final Map<Vendor, VendorAdapter> adapters;

    public VendorAdapterFactory(List<VendorAdapter> allAdapters) {
        this.adapters = allAdapters.stream()
                .collect(Collectors.toMap(VendorAdapter::getVendor, Function.identity()));
    }

    public VendorAdapter getAdapter(Vendor vendor) {
        VendorAdapter adapter = adapters.get(vendor);
        if (adapter == null) {
            throw new IllegalArgumentException("Niciun VendorAdapter inregistrat pentru: " + vendor);
        }
        return adapter;
    }
}
