/*
 * This file is part of NavalPlan
 *
 * Copyright (C) 2009 Fundación para o Fomento da Calidade Industrial e
 *                    Desenvolvemento Tecnolóxico de Galicia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.navalplanner.business.materials.entities;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.hibernate.validator.AssertTrue;
import org.hibernate.validator.NotEmpty;
import org.hibernate.validator.Valid;
import org.navalplanner.business.common.IntegrationEntity;
import org.navalplanner.business.common.Registry;
import org.navalplanner.business.common.exceptions.InstanceNotFoundException;
import org.navalplanner.business.materials.daos.IMaterialCategoryDAO;

/**
 * MaterialCategory entity
 *
 * @author Jacobo Aragunde Perez <jaragunde@igalia.com>
 *
 */
public class MaterialCategory extends IntegrationEntity {

    public static List<Material> getAllMaterialsFrom(
            Collection<? extends MaterialCategory> categories) {
        List<Material> result = new ArrayList<Material>();
        for (MaterialCategory each : categories) {
            result.addAll(each.getMaterials());
        }
        return result;
    }

    @NotEmpty
    private String name;

    private MaterialCategory parent = null;

    @Valid
    private Set<MaterialCategory> subcategories = new HashSet<MaterialCategory>();

    @Valid
    private Set<Material> materials = new HashSet<Material>();

    // Default constructor, needed by Hibernate
    protected MaterialCategory() {

    }

    public static MaterialCategory create(String name) {
        return (MaterialCategory) create(new MaterialCategory(name));
    }

    public static MaterialCategory createUnvalidated(String code, String name) {
        MaterialCategory materialCategory = create(new MaterialCategory(), code);
        materialCategory.name = name;
        return materialCategory;
    }

    public void updateUnvalidated(String name) {

        if (!StringUtils.isBlank(name)) {
            this.name = name;
        }

    }

    protected MaterialCategory(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MaterialCategory getParent() {
        return parent;
    }

    public void setParent(MaterialCategory parent) {
        this.parent = parent;
    }

    public Set<MaterialCategory> getSubcategories() {
        return Collections.unmodifiableSet(subcategories);
    }

    public void addSubcategory (MaterialCategory subcategory) {
        subcategories.add(subcategory);
        subcategory.setParent(this);
    }

    public void removeSubcategory (MaterialCategory subcategory) {
        subcategories.remove(subcategory);
    }

    public Set<Material> getMaterials() {
        return Collections.unmodifiableSet(materials);
    }

    public void addMaterial(Material material) {
        materials.add(material);
        material.setCategory(this);
    }

    public void removeMaterial(Material material) {
        materials.remove(material);
    }

    @AssertTrue(message="material category name has to be unique. It is already used")
    public boolean checkConstraintUniqueName() {
        boolean result;
        if (isNewObject()) {
            result = !existsMaterialCategoryWithTheName();
        } else {
            result = isIfExistsTheExistentMaterialCategoryThisOne();
        }
        return result;
    }

    private boolean existsMaterialCategoryWithTheName() {
        IMaterialCategoryDAO materialCategoryDAO = Registry.getMaterialCategoryDAO();
        return materialCategoryDAO.existsMaterialCategoryWithNameInAnotherTransaction(name);
    }

    private boolean isIfExistsTheExistentMaterialCategoryThisOne() {
        IMaterialCategoryDAO materialCategoryDAO = Registry.getMaterialCategoryDAO();
        try {
            MaterialCategory materialCategory =
                materialCategoryDAO.findUniqueByNameInAnotherTransaction(name);
            return materialCategory.getId().equals(getId());
        } catch (InstanceNotFoundException e) {
            return true;
        }
    }

    public Material getMaterialByCode(String code)
            throws InstanceNotFoundException {

        if (StringUtils.isBlank(code)) {
            throw new InstanceNotFoundException(code, Material.class.getName());
        }

        for (Material m : this.materials) {
            if (m.getCode().equalsIgnoreCase(StringUtils.trim(code))) {
                return m;
            }
        }

        throw new InstanceNotFoundException(code, Material.class.getName());

    }

    public MaterialCategory getSubcategoryByCode(String code)
            throws InstanceNotFoundException {

        if (StringUtils.isBlank(code)) {
            throw new InstanceNotFoundException(code, MaterialCategory.class
                    .getName());
        }

        for (MaterialCategory s : this.subcategories) {
            if (s.getCode().equalsIgnoreCase(StringUtils.trim(code))) {
                return s;
            }
        }

        throw new InstanceNotFoundException(code, MaterialCategory.class
                .getName());

    }

    @Override
    protected IMaterialCategoryDAO getIntegrationEntityDAO() {
        return Registry.getMaterialCategoryDAO();
    }

    @SuppressWarnings("unused")
    @AssertTrue(message = "The subcategories names must be unique.")
    public boolean checkConstraintUniqueSubcategoryName() {
        Set<String> subcategoriesNames = new HashSet<String>();
        for (MaterialCategory mc : this.getAllSubcategories()) {
            if (!StringUtils.isBlank(mc.getName())) {
                String name = StringUtils.deleteWhitespace(mc.getName()
                        .toLowerCase());
                if (subcategoriesNames.contains(name)) {
                    return false;
                } else {
                    subcategoriesNames.add(name);
                }
            }
        }
        return true;
    }

    @AssertTrue
    public boolean checkConstraintNonRepeatedMaterialCategoryCodes() {
        Set<MaterialCategory> allSubcategories = getAllSubcategories();
        allSubcategories.add(this);
        return getFirstRepeatedCode(allSubcategories) == null;
    }

    private Set<MaterialCategory> getAllSubcategories() {
        Set<MaterialCategory> result = new HashSet<MaterialCategory>(subcategories);
        for (MaterialCategory subcategory : subcategories) {
            result.addAll(subcategory.getAllSubcategories());
        }
        return result;
    }

    @AssertTrue
    public boolean checkConstraintNonRepeatedMaterialCodes() {
        Set<Material> allMaterials = getAllMaterials();
        return getFirstRepeatedCode(allMaterials) == null;
    }

    private Set<Material> getAllMaterials() {
        Set<Material> result = new HashSet<Material>(materials);
        for (MaterialCategory subcategory : subcategories) {
            result.addAll(subcategory.getAllMaterials());
        }
        return result;
    }
}
