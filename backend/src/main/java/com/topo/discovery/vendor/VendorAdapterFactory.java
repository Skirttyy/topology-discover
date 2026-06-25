package com.topo.discovery.vendor;

import com.topo.discovery.model.Vendor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class VendorAdapterFactory {

    private final Map<Vendor, VendorAdapter> adapters;

    public VendorAdapterFactory(List<VendorAdapter> allAdapters) {
        this.adapters = allAdapters.stream()
                .collect(Collectors.toMap(VendorAdapter::getVendor, Function.identity()));
    }

    /**
     * Returneaza adapter-ul pentru vendor-ul dat.
     * Pentru UNKNOWN, returneaza Juniper ca fallback (comenzile "show version"
     * si "show hostname" sunt prezente pe majoritatea vendor-ilor).
     * Daca nu gaseste, fallback la Juniper.
     */
    public VendorAdapter getAdapter(Vendor vendor) {
        VendorAdapter adapter = adapters.get(vendor);
        if (adapter == null) {
            return adapters.get(Vendor.JUNIPER); // fallback generic
        }
        return adapter;
    }
}
