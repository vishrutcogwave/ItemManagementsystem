package com.itemmanagement.service;

import com.itemmanagement.dto.auth.RegisterStoreRequest;
import com.itemmanagement.entity.Branch;
import com.itemmanagement.entity.Permission;
import com.itemmanagement.entity.Role;
import com.itemmanagement.entity.Store;
import com.itemmanagement.entity.TaxMaster;
import com.itemmanagement.entity.User;
import com.itemmanagement.entity.enums.StoreStatus;
import com.itemmanagement.entity.enums.TaxType;
import com.itemmanagement.exception.ApiException;
import com.itemmanagement.repository.BranchRepository;
import com.itemmanagement.repository.PermissionRepository;
import com.itemmanagement.repository.RoleRepository;
import com.itemmanagement.repository.StoreRepository;
import com.itemmanagement.repository.TaxMasterRepository;
import com.itemmanagement.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class StoreProvisioningService {

    public static final String STORE_OWNER = "STORE_OWNER";
    public static final String STORE_ADMIN = "STORE_ADMIN";
    public static final String BRANCH_MANAGER = "BRANCH_MANAGER";
    public static final String BRANCH_STAFF = "BRANCH_STAFF";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private static final List<String> OWNER_PERMISSIONS = List.of(
            "DASHBOARD_VIEW", "STORE_VIEW", "STORE_MANAGE", "BRANCH_VIEW", "BRANCH_MANAGE",
            "USER_VIEW", "USER_MANAGE", "ROLE_VIEW", "ROLE_MANAGE",
            "CATEGORY_VIEW", "CATEGORY_MANAGE", "ITEM_VIEW", "ITEM_MANAGE",
            "TAX_VIEW", "TAX_MANAGE", "SUPPLIER_VIEW", "SUPPLIER_MANAGE",
            "PURCHASE_VIEW", "PURCHASE_MANAGE",
            "INVENTORY_VIEW", "INVENTORY_ADJUST", "TRANSFER_VIEW", "TRANSFER_MANAGE", "TRANSFER_APPROVE",
            "SALES_VIEW", "SALES_MANAGE", "REPORT_VIEW");

    private static final List<String> ADMIN_PERMISSIONS = List.of(
            "DASHBOARD_VIEW", "STORE_VIEW", "BRANCH_VIEW", "USER_VIEW", "USER_MANAGE", "ROLE_VIEW",
            "CATEGORY_VIEW", "CATEGORY_MANAGE", "ITEM_VIEW", "ITEM_MANAGE",
            "TAX_VIEW", "TAX_MANAGE", "SUPPLIER_VIEW", "SUPPLIER_MANAGE",
            "PURCHASE_VIEW", "PURCHASE_MANAGE",
            "INVENTORY_VIEW", "INVENTORY_ADJUST", "TRANSFER_VIEW", "TRANSFER_MANAGE", "TRANSFER_APPROVE",
            "SALES_VIEW", "SALES_MANAGE", "REPORT_VIEW");

    private static final List<String> MANAGER_PERMISSIONS = List.of(
            "DASHBOARD_VIEW", "CATEGORY_VIEW", "ITEM_VIEW", "TAX_VIEW", "SUPPLIER_VIEW",
            "PURCHASE_VIEW", "PURCHASE_MANAGE", "INVENTORY_VIEW", "INVENTORY_ADJUST",
            "TRANSFER_VIEW", "TRANSFER_MANAGE", "SALES_VIEW", "SALES_MANAGE", "REPORT_VIEW");

    private static final List<String> STAFF_PERMISSIONS = List.of(
            "DASHBOARD_VIEW", "CATEGORY_VIEW", "ITEM_VIEW", "TAX_VIEW",
            "INVENTORY_VIEW", "SALES_VIEW", "SALES_MANAGE");

    private final StoreRepository storeRepository;
    private final BranchRepository branchRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;
    private final TaxMasterRepository taxMasterRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public ProvisionedStore createStore(RegisterStoreRequest request, boolean platformCreated) {
        String storeCode = request.storeCode().trim().toUpperCase(Locale.ROOT);
        if ("PLATFORM".equals(storeCode)) {
            throw ApiException.badRequest("PLATFORM is a reserved store code");
        }
        if (storeRepository.existsByStoreCodeIgnoreCase(storeCode)) {
            throw ApiException.conflict("Store code is already registered");
        }
        Store store = storeRepository.save(Store.builder()
                .storeCode(storeCode)
                .storeName(request.storeName().trim())
                .ownerName(request.ownerName().trim())
                .email(request.email().trim())
                .mobile(request.mobile().trim())
                .address(request.address().trim())
                .gstNumber(blankToNull(request.gstNumber()))
                .status(StoreStatus.ACTIVE)
                .build());

        Branch mainBranch = branchRepository.save(Branch.builder()
                .storeId(store.getId())
                .branchCode("MAIN")
                .branchName(request.branchName() == null || request.branchName().isBlank()
                        ? "Main Branch" : request.branchName().trim())
                .address(store.getAddress())
                .contactNumber(store.getMobile())
                .state(blankToNull(request.state()))
                .status(StoreStatus.ACTIVE)
                .build());

        seedDefaultTaxes(store.getId());
        Map<String, Role> roles = createDefaultRoles(store.getId());

        String username = request.username().trim();
        if (userRepository.existsByStoreIdAndUsernameIgnoreCase(store.getId(), username)) {
            throw ApiException.conflict("That username is already taken for this store");
        }
        User owner = userRepository.save(User.builder()
                .storeId(store.getId())
                .username(username)
                .email(request.email().trim())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.ownerName().trim())
                .mobile(request.mobile().trim())
                .status(StoreStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(roles.get(STORE_OWNER))))
                .branches(new HashSet<>(Set.of(mainBranch)))
                .build());

        return new ProvisionedStore(store, mainBranch, owner, roles);
    }

    @Transactional
    public Map<String, Role> createDefaultRoles(Long storeId) {
        Map<String, Role> roles = new LinkedHashMap<>();
        roles.put(STORE_OWNER, createRole(storeId, STORE_OWNER, "Store Owner",
                "Owns the store and can manage every branch and setting", OWNER_PERMISSIONS));
        roles.put(STORE_ADMIN, createRole(storeId, STORE_ADMIN, "Store Admin",
                "Handles day-to-day store operations across branches", ADMIN_PERMISSIONS));
        roles.put(BRANCH_MANAGER, createRole(storeId, BRANCH_MANAGER, "Branch Manager",
                "Manages inventory, purchases and sales for assigned branches", MANAGER_PERMISSIONS));
        roles.put(BRANCH_STAFF, createRole(storeId, BRANCH_STAFF, "Branch Staff",
                "Bills sales and views inventory for assigned branches", STAFF_PERMISSIONS));
        return roles;
    }

    public Role createRole(Long storeId, String code, String name, String description, List<String> permissionCodes) {
        Role role = Role.builder()
                .storeId(storeId)
                .code(code)
                .name(name)
                .description(description)
                .isSystem(true)
                .permissions(new HashSet<>(permissionRepository.findByCodeIn(permissionCodes)))
                .build();
        return roleRepository.save(role);
    }

    public Set<Permission> permissions(String... codes) {
        return new HashSet<>(permissionRepository.findByCodeIn(List.of(codes)));
    }

    public User createStoreUser(Long storeId, String username, String email, String password,
                                String fullName, String mobile, Role role, List<Branch> branches) {
        if (userRepository.existsByStoreIdAndUsernameIgnoreCase(storeId, username)) {
            throw ApiException.conflict("Username is already taken for this store");
        }
        return userRepository.save(User.builder()
                .storeId(storeId)
                .username(username.trim())
                .email(email.trim())
                .passwordHash(passwordEncoder.encode(password))
                .fullName(fullName.trim())
                .mobile(mobile)
                .status(StoreStatus.ACTIVE)
                .roles(new HashSet<>(Set.of(role)))
                .branches(new HashSet<>(branches))
                .build());
    }

    private void seedDefaultTaxes(Long storeId) {
        createTax(storeId, "GST 0% (Exempt)", "Nil rated or exempt supplies",
                "0.00", TaxType.EXEMPT);
        createTax(storeId, "GST 5%", "Standard 5% GST slab", "5.00", TaxType.GST);
        createTax(storeId, "GST 12%", "Standard 12% GST slab", "12.00", TaxType.GST);
        createTax(storeId, "GST 18%", "Standard 18% GST slab", "18.00", TaxType.GST);
        createTax(storeId, "GST 28%", "Standard 28% GST slab", "28.00", TaxType.GST);
    }

    private void createTax(Long storeId, String name, String description, String gst, TaxType type) {
        java.math.BigDecimal percentage = new java.math.BigDecimal(gst);
        java.math.BigDecimal half = percentage.divide(java.math.BigDecimal.valueOf(2), 2,
                java.math.RoundingMode.HALF_UP);
        taxMasterRepository.save(TaxMaster.builder()
                .storeId(storeId)
                .taxName(name)
                .description(description)
                .gstPercentage(percentage)
                .cgstPercentage(half)
                .sgstPercentage(percentage.subtract(half))
                .igstPercentage(percentage)
                .taxType(type)
                .status(StoreStatus.ACTIVE)
                .build());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ProvisionedStore(Store store, Branch mainBranch, User owner, Map<String, Role> roles) {
    }
}
