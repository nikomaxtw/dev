/*
 * This file is part of ###PROJECT_NAME###
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

package org.navalplanner.business.labels.entities;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang.Validate;
import org.hibernate.validator.NotEmpty;
import org.hibernate.validator.NotNull;
import org.navalplanner.business.common.BaseEntity;
import org.navalplanner.business.orders.entities.OrderElement;

/**
 * Label entity
 *
 * @author Diego Pino Garcia<dpino@igalia.com
 *
 */
public class Label extends BaseEntity {

    @NotEmpty
    private String name;

    @NotNull
    private LabelType type;

    private Set<OrderElement> orderElements = new HashSet<OrderElement>();

    // Default constructor, needed by Hibernate
    protected Label() {

    }

    public static Label create(String name) {
        return (Label) create(new Label(name));
    }

    protected Label(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LabelType getType() {
        return type;
    }

    public void setType(LabelType type) {
        this.type = type;
    }

    public Set<OrderElement> getOrderElements() {
        return Collections.unmodifiableSet(orderElements);
    }

    public void addOrderElement(OrderElement orderElement) {
        Validate.notNull(orderElement);
        orderElements.add(orderElement);
    }

    public void removeOrderElement(OrderElement orderElement) {
        orderElements.add(orderElement);
    }
}
