/*
 * Copyright (c) 2009 - 2019 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Red Hat trademarks are not licensed under GPLv3. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.subscriptions.tally.facts;

import static org.junit.jupiter.api.Assertions.*;

import org.candlepin.subscriptions.ApplicationProperties;
import org.candlepin.subscriptions.files.RhelProductListSource;
import org.candlepin.subscriptions.inventory.db.model.InventoryHostFacts;
import org.candlepin.subscriptions.util.ApplicationClock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;


@TestInstance(Lifecycle.PER_CLASS)
public class FactNormalizerTest {

    private FactNormalizer normalizer;
    private ApplicationClock clock;

    @BeforeAll
    public void setup() throws IOException {
        ApplicationProperties props = new ApplicationProperties();
        props.setRhelProductListResourceLocation("classpath:product_list.txt");

        RhelProductListSource source = new RhelProductListSource(props);
        source.setResourceLoader(new FileSystemResourceLoader());
        source.init();

        clock = new ApplicationClock();
        normalizer = new FactNormalizer(new ApplicationProperties(), source, clock);
    }

    @Test
    public void testRhsmNormalization() {
        NormalizedFacts normalized = normalizer.normalize(createRhsmHost(Arrays.asList("P1"), 12, 2));
        assertTrue(normalized.getProducts().contains("RHEL"));
        assertEquals(Integer.valueOf(12), normalized.getCores());
        assertEquals(Integer.valueOf(2), normalized.getSockets());
    }

    @Test
    public void testQpcNormalization() {
        InventoryHostFacts host = createQpcHost(true);
        NormalizedFacts normalized = normalizer.normalize(host);
        assertTrue(normalized.getProducts().contains("RHEL"));
        assertEquals(Integer.valueOf(0), normalized.getCores());
        assertEquals(Integer.valueOf(0), normalized.getSockets());
    }

    @Test
    public void testNormalizeNonRhelProduct() {
        NormalizedFacts normalized = normalizer.normalize(createRhsmHost(Arrays.asList("NonRHEL"), 4, 8));
        assertTrue(normalized.getProducts().isEmpty());
        assertEquals(Integer.valueOf(4), normalized.getCores());
        assertEquals(Integer.valueOf(8), normalized.getSockets());
    }

    @Test
    public void testNormalizeWhenProductsMissingFromFactsAndOnlyCoresAreSet() {
        NormalizedFacts normalized = normalizer.normalize(createRhsmHost(null, 4, null));
        assertNotNull(normalized.getProducts());
        assertTrue(normalized.getProducts().isEmpty());
        assertEquals(Integer.valueOf(4), normalized.getCores());
        assertEquals(Integer.valueOf(0), normalized.getSockets());
    }

    @Test
    public void testNormalizeWhenProductsMissingFromFactsAndOnlySocketsAreSet() {
        NormalizedFacts normalized = normalizer.normalize(createRhsmHost(null, null, 8));
        assertNotNull(normalized.getProducts());
        assertTrue(normalized.getProducts().isEmpty());
        assertEquals(Integer.valueOf(0), normalized.getCores());
        assertEquals(Integer.valueOf(8), normalized.getSockets());
    }

    @Test
    public void testNormalizeWhenCoresAndSocketsMissingFromFacts() {
        NormalizedFacts normalized = normalizer.normalize(createRhsmHost(Arrays.asList("P1"), null, null));
        assertTrue(normalized.getProducts().contains("RHEL"));
        assertEquals(Integer.valueOf(0), normalized.getCores());
        assertEquals(Integer.valueOf(0), normalized.getSockets());
    }

    @Test
    public void testIgnoresHostWhenLastSyncIsOutOfConfiguredThreshold() {
        OffsetDateTime lastSynced = clock.now().minusDays(2);
        InventoryHostFacts facts = createRhsmHost(Arrays.asList("P1"), 4, 8);
        facts.setSyncTimestamp(lastSynced.toString());

        NormalizedFacts normalized = normalizer.normalize(facts);
        assertTrue(normalized.getProducts().isEmpty());
        assertNull(normalized.getCores());
    }

    @Test
    public void testIncludesHostWhenLastSyncIsWithinTheConfiguredThreshold() {
        OffsetDateTime lastSynced = clock.now().minusDays(1);
        InventoryHostFacts facts = createRhsmHost(Arrays.asList("P1"), 4, 8);
        facts.setSyncTimestamp(lastSynced.toString());

        NormalizedFacts normalized = normalizer.normalize(facts);
        assertTrue(normalized.getProducts().contains("RHEL"));
        assertEquals(Integer.valueOf(4), normalized.getCores());
    }

    @Test
    void testRhelFromQpcFacts() {
        NormalizedFacts normalized = normalizer.normalize(createQpcHost(true));
        assertTrue(normalized.getProducts().contains("RHEL"));
    }

    @Test
    public void testEmptyProductListWhenIsRhelIsFalse() {
        NormalizedFacts normalized = normalizer.normalize(createQpcHost(false));
        assertTrue(normalized.getProducts().isEmpty());
    }

    @Test
    public void testEmptyProductListWhenIsRhelNotSet() {
        NormalizedFacts normalized = normalizer.normalize(createQpcHost(null));
        assertTrue(normalized.getProducts().isEmpty());
    }

    private InventoryHostFacts createRhsmHost(List<String> products, Integer cores, Integer sockets) {
        return new InventoryHostFacts("Account", "Test System", "test_org", String.valueOf(cores),
            String.valueOf(sockets), null,
            StringUtils.collectionToCommaDelimitedString(products), clock.now().toString());
    }

    private InventoryHostFacts createQpcHost(Boolean isRhel) {
        return new InventoryHostFacts("Account", "Test System", "test_org", null,
            null, isRhel == null ? null : isRhel.toString(),
            null, clock.now().toString());
    }

}
