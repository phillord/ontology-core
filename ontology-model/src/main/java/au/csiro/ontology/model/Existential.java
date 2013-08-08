/**
 * Copyright CSIRO Australian e-Health Research Centre (http://aehrc.com).
 * All rights reserved. Use is subject to license terms and conditions.
 */
package au.csiro.ontology.model;

/**
 * This class represents an existential (also known as an ObjectSomeValuesFrom
 * in OWL).
 * 
 * @author Alejandro Metke
 * 
 */
public class Existential implements IExistential {

    private INamedRole role;
    private IConcept concept;
    
    /**
     * 
     */
    public Existential() {
        
    }
    
    /**
     * 
     * @param role
     * @param concept
     */
    public Existential(INamedRole role, IConcept concept) {
        this.role = role;
        this.concept = concept;
    }

    @Override
    public String toString() {
        return role + "." + concept;
    }
    
    public INamedRole getRole() {
        return role;
    }
    
    public IConcept getConcept() {
        return concept;
    }

    /**
     * @param role the role to set
     */
    public void setRole(INamedRole role) {
        this.role = role;
    }

    /**
     * @param concept the concept to set
     */
    public void setConcept(IConcept concept) {
        this.concept = concept;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((concept == null) ? 0 : concept.hashCode());
        result = prime * result + ((role == null) ? 0 : role.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Existential other = (Existential) obj;
        if (concept == null) {
            if (other.concept != null)
                return false;
        } else if (!concept.equals(other.concept))
            return false;
        if (role == null) {
            if (other.role != null)
                return false;
        } else if (!role.equals(other.role))
            return false;
        return true;
    }

    @SuppressWarnings({ "rawtypes" })
    public int compareTo(IConcept o) {
        Class thisClass = this.getClass();
        Class otherClass = o.getClass();
        if(thisClass.equals(otherClass)) {
            Existential other = (Existential)o;
            int res = 0;
            res = role.getId().compareTo(other.role.getId());
            if(res != 0) return res;
            try {
                res = concept.compareTo(other.concept);
            } catch(ClassCastException e) {
                // Need to catch this because elements in the conjunction might
                // be of different types
                res = concept.getClass().toString().compareTo(
                        other.concept.getClass().toString());
            }
            if(res != 0) return res;
            return 0;
        } else {
            return thisClass.toString().compareTo(otherClass.toString());
        }
    }

}
