package org.navalplanner.web.calendars;

import java.util.Date;
import java.util.List;

import org.apache.commons.lang.Validate;
import org.hibernate.validator.ClassValidator;
import org.hibernate.validator.InvalidValue;
import org.navalplanner.business.calendars.daos.IBaseCalendarDAO;
import org.navalplanner.business.calendars.entities.BaseCalendar;
import org.navalplanner.business.calendars.entities.ExceptionDay;
import org.navalplanner.business.calendars.entities.BaseCalendar.DayType;
import org.navalplanner.business.calendars.entities.BaseCalendar.Days;
import org.navalplanner.business.common.exceptions.InstanceNotFoundException;
import org.navalplanner.business.common.exceptions.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * Model for UI operations related to {@link BaseCalendar}.
 *
 * @author Manuel Rego Casasnovas <mrego@igalia.com>
 */
@Service
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class BaseCalendarModel implements IBaseCalendarModel {

    /**
     * Conversation state
     */
    private BaseCalendar baseCalendar;

    private Date selectedDate;

    private boolean editing = false;

    private ClassValidator<BaseCalendar> baseCalendarValidator = new ClassValidator<BaseCalendar>(
            BaseCalendar.class);

    @Autowired
    private IBaseCalendarDAO baseCalendarDAO;


    /*
     * Non conversational steps
     */

    @Override
    @Transactional(readOnly = true)
    public List<BaseCalendar> getBaseCalendars() {
        return baseCalendarDAO.getBaseCalendars();
    }


    /*
     * Initial conversation steps
     */

    @Override
    public void initCreate() {
        editing = false;
        this.baseCalendar = BaseCalendar.create();
    }

    @Override
    @Transactional(readOnly = true)
    public void initEdit(BaseCalendar baseCalendar) {
        editing = true;
        Validate.notNull(baseCalendar);

        this.baseCalendar = getFromDB(baseCalendar);
        forceLoadExceptionDays();
    }

    private void forceLoadExceptionDays() {
        baseCalendar.getHoursPerDay().size();
        baseCalendar.getExceptions().size();
    }

    private BaseCalendar getFromDB(BaseCalendar baseCalendar) {
        return getFromDB(baseCalendar.getId());
    }

    @Override
    public void initRemove(BaseCalendar baseCalendar) {
        this.baseCalendar = baseCalendar;
    }

    @Transactional(readOnly = true)
    private BaseCalendar getFromDB(Long id) {
        try {
            BaseCalendar baseCalendar = baseCalendarDAO.find(id);
            return baseCalendar;
        } catch (InstanceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /*
     * Intermediate conversation steps
     */

    @Override
    public BaseCalendar getBaseCalendar() {
        return baseCalendar;
    }

    @Override
    public boolean isEditing() {
        return this.editing;
    }

    @Override
    public void selectDay(Date date) {
        this.selectedDate = date;
    }

    @Override
    @Transactional(readOnly = true)
    public Integer getHoursOfDay() {
        if (baseCalendar == null) {
            return null;
        }

        return baseCalendar.getWorkableHours(selectedDate);
    }

    @Override
    @Transactional(readOnly = true)
    public DayType getTypeOfDay() {
        if (baseCalendar == null) {
            return null;
        }

        return baseCalendar.getType(selectedDate);
    }

    @Override
    @Transactional(readOnly = true)
    public void createException(Integer hours) {
        if (getTypeOfDay().equals(DayType.OWN_EXCEPTION)) {
            baseCalendar.updateExceptionDay(selectedDate, hours);
        } else {
            ExceptionDay day = ExceptionDay.create(selectedDate, hours);
            baseCalendar.addExceptionDay(day);
        }
    }

    @Override
    public Integer getHours(Days day) {
        if (baseCalendar == null) {
            return null;
        }

        return baseCalendar.getHours(day);
    }

    @Override
    public Boolean isDefault(Days day) {
        if (baseCalendar == null) {
            return false;
        }

        return baseCalendar.isDefault(day);
    }

    @Override
    public void setDefault(Days day) {
        if (baseCalendar != null) {
            baseCalendar.setDefault(day);
        }
    }

    @Override
    public void setHours(Days day, Integer hours) {
        if (baseCalendar != null) {
            baseCalendar.setHours(day, hours);
        }
    }

    @Override
    public boolean isExceptional() {
        if (baseCalendar == null) {
            return false;
        }

        ExceptionDay day = baseCalendar.getOwnExceptionDay(selectedDate);
        return (day != null);
    }

    @Override
    public void removeException() {
        baseCalendar.removeExceptionDay(selectedDate);
    }

    @Override
    public boolean isDerived() {
        if (baseCalendar == null) {
            return false;
        }

        return baseCalendar.isDerived();
    }

    /*
     * Final conversation steps
     */

    @Override
    @Transactional
    public void confirmSave() throws ValidationException {
        InvalidValue[] invalidValues = baseCalendarValidator
                .getInvalidValues(baseCalendar);
        if (invalidValues.length > 0) {
            throw new ValidationException(invalidValues);
        }

        baseCalendar.checkValid();
        baseCalendarDAO.save(baseCalendar);
    }

    @Override
    @Transactional
    public void confirmRemove() {
        try {
            baseCalendarDAO.remove(baseCalendar.getId());
        } catch (InstanceNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cancel() {
        resetState();
    }

    private void resetState() {
        baseCalendar = null;
    }

}
