package com.itemmanagement.config;

import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.BranchInventory;
import com.itemmanagement.entity.Category;
import com.itemmanagement.entity.InventoryTransaction;
import com.itemmanagement.entity.Item;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.Supplier;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TransactionType;
import com.itemmanagement.entity.enums.Unit;
import com.itemmanagement.repository.BranchInventoryRepository;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.CategoryRepository;
import com.itemmanagement.repository.InventoryTransactionRepository;
import com.itemmanagement.repository.ItemRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.SupplierRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.service.StoreProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder {

    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final SupplierRepository supplierRepository;
    private final BranchInventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final StoreProvisioningService provisioningService;

    @Value("${app.seed-demo:true}")
    private boolean enabled;

    @Transactional
    public void seed() {
        if (!enabled || storeRepository.findByStoreCodeIgnoreCase("ABC001").isPresent()) {
            return;
        }
        RegisterStoreRequest request = new RegisterStoreRequest(
                "ABC001", "ABC Dairy", "Arjun Mehta", "owner@abcdairy.test", "9876543210",
                "14, MG Road, Bengaluru, Karnataka 560001", "29ABCDE1234F1Z5", "Karnataka",
                "owner", "Owner@123", "Main Branch");

        StoreProvisioningService.ProvisionedStore provisioned = provisioningService.createStore(request, true);
        Long storeId = provisioned.store().getId();
        Map<String, Role> roles = provisioned.roles();
        Map<String, TaxMaster> taxes = taxMasterRepository
                .findByStoreIdOrderByGstPercentageAscTaxNameAsc(storeId).stream()
                .collect(Collectors.toMap(TaxMaster::getTaxName, Function.identity()));

        Branch bangalore = branchRepository.save(Branch.builder()
                .storeId(storeId).branchCode("BLR").branchName("Bangalore Branch")
                .address("22, Indiranagar, Bengaluru 560038").contactNumber("9876543211")
                .state("Karnataka").status(StoreStatus.ACTIVE).build());
        Branch mysore = branchRepository.save(Branch.builder()
                .storeId(storeId).branchCode("MYS").branchName("Mysore Branch")
                .address("7, Sayyaji Rao Road, Mysore 570001").contactNumber("9876543212")
                .state("Karnataka").status(StoreStatus.ACTIVE).build());
        Branch main = provisioned.mainBranch();

        provisioningService.createStoreUser(storeId, "manager", "manager@abcdairy.test", "Manager@123",
                "Priya Sharma", "9876543213", roles.get(StoreProvisioningService.BRANCH_MANAGER),
                List.of(main, bangalore));
        provisioningService.createStoreUser(storeId, "staff", "staff@abcdairy.test", "Staff@123",
                "Rahul Kumar", "9876543214", roles.get(StoreProvisioningService.BRANCH_STAFF),
                List.of(main));

        Map<String, Category> categories = new LinkedHashMap<>();
        categories.put("Milk Products", categoryRepository.save(Category.builder()
                .storeId(storeId).categoryName("Milk Products")
                .description("Fresh milk, curd, paneer and dairy essentials")
                .status(StoreStatus.ACTIVE).build()));
        categories.put("Beverages", categoryRepository.save(Category.builder()
                .storeId(storeId).categoryName("Beverages")
                .description("Water, juices and soft drinks").status(StoreStatus.ACTIVE).build()));
        categories.put("Grocery", categoryRepository.save(Category.builder()
                .storeId(storeId).categoryName("Grocery")
                .description("Daily staples and cooking ingredients").status(StoreStatus.ACTIVE).build()));
        categories.put("Snacks", categoryRepository.save(Category.builder()
                .storeId(storeId).categoryName("Snacks")
                .description("Biscuits, chips and quick bites").status(StoreStatus.ACTIVE).build()));
        categories.put("Cleaning Products", categoryRepository.save(Category.builder()
                .storeId(storeId).categoryName("Cleaning Products")
                .description("Household cleaning supplies").status(StoreStatus.ACTIVE).build()));

        createItem(storeId, categories, taxes, "Milk Products", "MILK-1L", "Milk", Unit.LITER,
                "60.00", "8", "GST 0% (Exempt)");
        createItem(storeId, categories, taxes, "Milk Products", "CURD-500", "Curd 500g", Unit.PIECE,
                "40.00", "5", "GST 5%");
        createItem(storeId, categories, taxes, "Milk Products", "PANEER-250", "Paneer 250g", Unit.PIECE,
                "100.00", "3", "GST 5%");
        createItem(storeId, categories, taxes, "Beverages", "WATER-1L", "Water Bottle 1L", Unit.BOTTLE,
                "15.00", "12", "GST 18%");
        createItem(storeId, categories, taxes, "Beverages", "JUICE-1L", "Mango Juice 1L", Unit.BOTTLE,
                "90.00", "6", "GST 12%");
        createItem(storeId, categories, taxes, "Grocery", "SUGAR-1KG", "Sugar", Unit.KG,
                "50.00", "10", "GST 5%");
        createItem(storeId, categories, taxes, "Grocery", "RICE-5KG", "Basmati Rice 5kg", Unit.PACKET,
                "450.00", "4", "GST 5%");
        createItem(storeId, categories, taxes, "Snacks", "BISCUIT-100", "Marie Biscuit 100g", Unit.PACKET,
                "25.00", "10", "GST 18%");
        createItem(storeId, categories, taxes, "Cleaning Products", "DETERGENT-1KG", "Detergent Powder 1kg", Unit.KG,
                "120.00", "5", "GST 18%");

        supplierRepository.save(Supplier.builder()
                .storeId(storeId).supplierName("Nandini Distributors").mobile("9988776655")
                .address("Dairy Circle, Bengaluru 560029").state("Karnataka")
                .gstNumber("29AABCN1234M1Z2")
                .email("orders@nandini.example").status(StoreStatus.ACTIVE).build());
        supplierRepository.save(Supplier.builder()
                .storeId(storeId).supplierName("FreshMart Wholesale").mobile("9977665544")
                .address("APMC Yard, Bengaluru 560022").state("Karnataka")
                .gstNumber("29AAFMS4321P1Z9")
                .email("sales@freshmart.example").status(StoreStatus.ACTIVE).build());
        supplierRepository.save(Supplier.builder()
                .storeId(storeId).supplierName("Ganga Beverages").mobile("9966554433")
                .address("Peenya Industrial Area, Bengaluru 560058").state("Karnataka")
                .gstNumber("29AAGCB9876Q1Z4")
                .email("supply@gangabev.example").status(StoreStatus.ACTIVE).build());
        supplierRepository.save(Supplier.builder()
                .storeId(storeId).supplierName("Chennai Snacks Depot").mobile("9955443322")
                .address("T Nagar, Chennai 600017").state("Tamil Nadu")
                .gstNumber("33AACCS1122R1Z8")
                .email("orders@chennaisnacks.example").status(StoreStatus.ACTIVE).build());

        Map<String, Item> items = itemRepository.findByStoreIdOrderByItemName(storeId).stream()
                .collect(Collectors.toMap(Item::getItemCode, Function.identity()));
        seedStock(storeId, main, items, Map.of(
                "MILK-1L", 40, "CURD-500", 20, "PANEER-250", 10, "WATER-1L", 30,
                "JUICE-1L", 12, "SUGAR-1KG", 25, "RICE-5KG", 8, "BISCUIT-100", 30,
                "DETERGENT-1KG", 15));
        seedStock(storeId, bangalore, items, Map.of(
                "MILK-1L", 18, "CURD-500", 8, "WATER-1L", 20, "JUICE-1L", 6,
                "SUGAR-1KG", 10, "BISCUIT-100", 12));
        seedStock(storeId, mysore, items, Map.of(
                "MILK-1L", 6, "WATER-1L", 10, "SUGAR-1KG", 4, "BISCUIT-100", 8,
                "DETERGENT-1KG", 5));

        log.info("Seeded demo store ABC Dairy with 3 branches and opening stock");
    }

    private void seedStock(Long storeId, Branch branch, Map<String, Item> items,
                           Map<String, Integer> stockByCode) {
        stockByCode.forEach((code, quantity) -> {
            Item item = items.get(code);
            if (item == null || quantity <= 0) {
                return;
            }
            BigDecimal stock = BigDecimal.valueOf(quantity);
            inventoryRepository.save(BranchInventory.builder()
                    .storeId(storeId)
                    .branchId(branch.getId())
                    .itemId(item.getId())
                    .currentStock(stock)
                    .minimumStock(item.getMinStockLevel())
                    .purchasePrice(item.getRate())
                    .sellingPrice(item.getRate())
                    .avgCost(item.getRate())
                    .build());
            transactionRepository.save(InventoryTransaction.builder()
                    .storeId(storeId)
                    .branchId(branch.getId())
                    .itemId(item.getId())
                    .transactionType(TransactionType.OPENING_STOCK)
                    .referenceType("OPENING")
                    .referenceNumber("OPENING")
                    .quantityIn(stock)
                    .quantityOut(BigDecimal.ZERO)
                    .balanceAfter(stock)
                    .notes("Opening stock")
                    .transactionDate(Instant.now())
                    .build());
        });
    }

    private void createItem(Long storeId, Map<String, Category> categories, Map<String, TaxMaster> taxes,
                            String categoryName, String code, String name, Unit unit,
                            String rate, String minStock, String taxName) {
        itemRepository.save(Item.builder()
                .storeId(storeId)
                .categoryId(categories.get(categoryName).getId())
                .taxId(taxes.get(taxName).getId())
                .itemCode(code)
                .itemName(name)
                .baseUnit(unit)
                .rate(new BigDecimal(rate))
                .minStockLevel(new BigDecimal(minStock))
                .status(StoreStatus.ACTIVE)
                .build());
    }
}
